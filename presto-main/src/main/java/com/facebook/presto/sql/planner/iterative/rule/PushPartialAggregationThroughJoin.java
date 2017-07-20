/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner.iterative.rule;

import com.facebook.presto.matching.Pattern;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.SymbolsExtractor;
import com.facebook.presto.sql.planner.iterative.Rule;
import com.facebook.presto.sql.planner.optimizations.SymbolMapper;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.facebook.presto.SystemSessionProperties.isPushAggregationThroughJoin;
import static com.facebook.presto.sql.planner.SymbolsExtractor.extractUnique;
import static com.facebook.presto.sql.planner.plan.AggregationNode.Step.PARTIAL;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Sets.intersection;

public class PushPartialAggregationThroughJoin
        implements Rule
{
    private static final Pattern PATTERN = Pattern.typeOf(AggregationNode.class);

    @Override
    public Pattern getPattern()
    {
        return PATTERN;
    }

    @Override
    public Optional<PlanNode> apply(PlanNode node, Context context)
    {
        if (!isPushAggregationThroughJoin(context.getSession())) {
            return Optional.empty();
        }

        AggregationNode aggregationNode = (AggregationNode) node;

        if (aggregationNode.getStep() != PARTIAL || aggregationNode.getGroupingSets().size() != 1) {
            return Optional.empty();
        }

        if (aggregationNode.getHashSymbol().isPresent()) {
            // TODO: add support for hash symbol in aggregation node
            return Optional.empty();
        }

        PlanNode childNode = context.getLookup().resolve(aggregationNode.getSource());
        if (!(childNode instanceof JoinNode)) {
            return Optional.empty();
        }

        JoinNode joinNode = (JoinNode) childNode;

        if (joinNode.getType() != JoinNode.Type.INNER || joinNode.getFilter().isPresent()) {
            // TODO: add support for filter function.
            // All availableSymbols used in filter function could be added to pushedDownGroupingSet
            return Optional.empty();
        }

        // TODO: leave partial aggregation above Join?
        if (allAggregationsOn(aggregationNode.getAggregations(), joinNode.getLeft().getOutputSymbols())) {
            return Optional.of(pushPartialToLeftChild(aggregationNode, joinNode));
        }
        else if (allAggregationsOn(aggregationNode.getAggregations(), joinNode.getRight().getOutputSymbols())) {
            return Optional.of(pushPartialToRightChild(aggregationNode, joinNode));
        }

        return Optional.empty();
    }

    private boolean allAggregationsOn(Map<Symbol, AggregationNode.Aggregation> aggregations, List<Symbol> symbols)
    {
        Set<Symbol> inputs = extractUnique(aggregations.values().stream().map(AggregationNode.Aggregation::getCall).collect(toImmutableList()));
        return symbols.containsAll(inputs);
    }

    private PlanNode pushPartialToLeftChild(AggregationNode node, JoinNode child)
    {
        Set<Symbol> joinLeftChildSymbols = ImmutableSet.copyOf(child.getLeft().getOutputSymbols());
        List<Symbol> groupingSet = getPushedDownGroupingSet(node, joinLeftChildSymbols, intersection(getJoinRequiredSymbols(child), joinLeftChildSymbols));
        AggregationNode pushedAggregation = replaceAggregationSource(node, child.getLeft(), child.getCriteria(), groupingSet);
        return pushPartialToJoin(pushedAggregation, child, pushedAggregation, child.getRight(), child.getRight().getOutputSymbols());
    }

    private PlanNode pushPartialToRightChild(AggregationNode node, JoinNode child)
    {
        Set<Symbol> joinRightChildSymbols = ImmutableSet.copyOf(child.getRight().getOutputSymbols());
        List<Symbol> groupingSet = getPushedDownGroupingSet(node, joinRightChildSymbols, intersection(getJoinRequiredSymbols(child), joinRightChildSymbols));
        AggregationNode pushedAggregation = replaceAggregationSource(node, child.getRight(), child.getCriteria(), groupingSet);
        return pushPartialToJoin(pushedAggregation, child, child.getLeft(), pushedAggregation, child.getLeft().getOutputSymbols());
    }

    private Set<Symbol> getJoinRequiredSymbols(JoinNode node)
    {
        return Streams.concat(
                node.getCriteria().stream().map(JoinNode.EquiJoinClause::getLeft),
                node.getCriteria().stream().map(JoinNode.EquiJoinClause::getRight),
                node.getFilter().map(SymbolsExtractor::extractUnique).orElse(ImmutableSet.of()).stream(),
                node.getLeftHashSymbol().map(ImmutableSet::of).orElse(ImmutableSet.of()).stream(),
                node.getRightHashSymbol().map(ImmutableSet::of).orElse(ImmutableSet.of()).stream())
                .collect(toImmutableSet());
    }

    private List<Symbol> getPushedDownGroupingSet(AggregationNode aggregation, Set<Symbol> availableSymbols, Set<Symbol> requiredJoinSymbols)
    {
        List<Symbol> groupingSet = Iterables.getOnlyElement(aggregation.getGroupingSets());

        // keep symbols that are directly from the join's child (availableSymbols)
        List<Symbol> pushedDownGroupingSet = groupingSet.stream()
                .filter(availableSymbols::contains)
                .collect(Collectors.toList());

        // add missing required join symbols to grouping set
        Set<Symbol> existingSymbols = new HashSet<>(pushedDownGroupingSet);
        requiredJoinSymbols.stream()
                .filter(existingSymbols::add)
                .forEach(pushedDownGroupingSet::add);

        return pushedDownGroupingSet;
    }

    private AggregationNode replaceAggregationSource(
            AggregationNode aggregation,
            PlanNode source,
            List<JoinNode.EquiJoinClause> criteria,
            List<Symbol> groupingSet)
    {
        ImmutableSet<Symbol> sourceSymbols = ImmutableSet.copyOf(source.getOutputSymbols());
        ImmutableMap.Builder<Symbol, Symbol> mapping = ImmutableMap.builder();

        for (JoinNode.EquiJoinClause joinClause : criteria) {
            if (sourceSymbols.contains(joinClause.getLeft())) {
                mapping.put(joinClause.getRight(), joinClause.getLeft());
            }
            else {
                mapping.put(joinClause.getLeft(), joinClause.getRight());
            }
        }

        AggregationNode pushedAggregation = new AggregationNode(
                aggregation.getId(),
                aggregation.getSource(),
                aggregation.getAggregations(),
                ImmutableList.of(groupingSet),
                aggregation.getStep(),
                aggregation.getHashSymbol(),
                aggregation.getGroupIdSymbol());
        return new SymbolMapper(mapping.build()).map(pushedAggregation, source);
    }

    private PlanNode pushPartialToJoin(
            AggregationNode pushedAggregation,
            JoinNode child,
            PlanNode leftChild,
            PlanNode rightChild,
            Collection<Symbol> otherSymbols)
    {
        ImmutableList.Builder<Symbol> outputSymbols = ImmutableList.builder();
        outputSymbols.addAll(pushedAggregation.getOutputSymbols());
        outputSymbols.addAll(otherSymbols);

        return new JoinNode(
                child.getId(),
                child.getType(),
                leftChild,
                rightChild,
                child.getCriteria(),
                outputSymbols.build(),
                child.getFilter(),
                child.getLeftHashSymbol(),
                child.getRightHashSymbol(),
                child.getDistributionType());
    }
}