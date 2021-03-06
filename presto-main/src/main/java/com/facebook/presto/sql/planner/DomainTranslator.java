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
package com.facebook.presto.sql.planner;

import com.facebook.presto.Session;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.spi.predicate.DiscreteValues;
import com.facebook.presto.spi.predicate.Domain;
import com.facebook.presto.spi.predicate.Marker;
import com.facebook.presto.spi.predicate.NullableValue;
import com.facebook.presto.spi.predicate.Range;
import com.facebook.presto.spi.predicate.Ranges;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.spi.predicate.ValueSet;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.FunctionInvoker;
import com.facebook.presto.sql.analyzer.ExpressionAnalyzer;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.tree.AstVisitor;
import com.facebook.presto.sql.tree.BetweenPredicate;
import com.facebook.presto.sql.tree.BooleanLiteral;
import com.facebook.presto.sql.tree.ComparisonExpression;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.InListExpression;
import com.facebook.presto.sql.tree.InPredicate;
import com.facebook.presto.sql.tree.IsNotNullPredicate;
import com.facebook.presto.sql.tree.IsNullPredicate;
import com.facebook.presto.sql.tree.LogicalBinaryExpression;
import com.facebook.presto.sql.tree.LongLiteral;
import com.facebook.presto.sql.tree.NotExpression;
import com.facebook.presto.sql.tree.NullLiteral;
import com.facebook.presto.sql.tree.QualifiedNameReference;
import com.facebook.presto.type.TypeRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.math.DoubleMath;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.sql.ExpressionUtils.and;
import static com.facebook.presto.sql.ExpressionUtils.combineConjuncts;
import static com.facebook.presto.sql.ExpressionUtils.combineDisjunctsWithDefault;
import static com.facebook.presto.sql.ExpressionUtils.or;
import static com.facebook.presto.sql.planner.LiteralInterpreter.toExpression;
import static com.facebook.presto.sql.tree.BooleanLiteral.FALSE_LITERAL;
import static com.facebook.presto.sql.tree.BooleanLiteral.TRUE_LITERAL;
import static com.facebook.presto.sql.tree.ComparisonExpression.Type.EQUAL;
import static com.facebook.presto.sql.tree.ComparisonExpression.Type.GREATER_THAN;
import static com.facebook.presto.sql.tree.ComparisonExpression.Type.GREATER_THAN_OR_EQUAL;
import static com.facebook.presto.sql.tree.ComparisonExpression.Type.LESS_THAN;
import static com.facebook.presto.sql.tree.ComparisonExpression.Type.LESS_THAN_OR_EQUAL;
import static com.facebook.presto.sql.tree.ComparisonExpression.Type.NOT_EQUAL;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.math.RoundingMode.CEILING;
import static java.math.RoundingMode.FLOOR;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public final class DomainTranslator
{
    private DomainTranslator()
    {
    }

    public static Expression toPredicate(TupleDomain<Symbol> tupleDomain)
    {
        if (tupleDomain.isNone()) {
            return FALSE_LITERAL;
        }
        ImmutableList.Builder<Expression> conjunctBuilder = ImmutableList.builder();
        for (Map.Entry<Symbol, Domain> entry : tupleDomain.getDomains().get().entrySet()) {
            Symbol symbol = entry.getKey();
            QualifiedNameReference reference = new QualifiedNameReference(symbol.toQualifiedName());
            conjunctBuilder.add(toPredicate(entry.getValue(), reference));
        }
        return combineConjuncts(conjunctBuilder.build());
    }

    private static Expression toPredicate(Domain domain, QualifiedNameReference reference)
    {
        if (domain.getValues().isNone()) {
            return domain.isNullAllowed() ? new IsNullPredicate(reference) : FALSE_LITERAL;
        }

        if (domain.getValues().isAll()) {
            return domain.isNullAllowed() ? TRUE_LITERAL : new NotExpression(new IsNullPredicate(reference));
        }

        List<Expression> disjuncts = new ArrayList<>();

        disjuncts.addAll(domain.getValues().getValuesProcessor().transform(
                ranges -> extractDisjuncts(domain.getType(), ranges, reference),
                discreteValues -> extractDisjuncts(domain.getType(), discreteValues, reference),
                allOrNone -> {
                    throw new IllegalStateException("Case should not be reachable");
                }));

        // Add nullability disjuncts
        if (domain.isNullAllowed()) {
            disjuncts.add(new IsNullPredicate(reference));
        }

        return combineDisjunctsWithDefault(disjuncts, TRUE_LITERAL);
    }

    private static List<Expression> extractDisjuncts(Type type, Ranges ranges, QualifiedNameReference reference)
    {
        List<Expression> disjuncts = new ArrayList<>();
        List<Expression> singleValues = new ArrayList<>();
        for (Range range : ranges.getOrderedRanges()) {
            checkState(!range.isAll()); // Already checked
            if (range.isSingleValue()) {
                singleValues.add(toExpression(range.getSingleValue(), type));
            }
            else if (isBetween(range)) {
                // Specialize the range with BETWEEN expression if possible b/c it is currently more efficient
                disjuncts.add(new BetweenPredicate(reference, toExpression(range.getLow().getValue(), type), toExpression(range.getHigh().getValue(), type)));
            }
            else {
                List<Expression> rangeConjuncts = new ArrayList<>();
                if (!range.getLow().isLowerUnbounded()) {
                    switch (range.getLow().getBound()) {
                        case ABOVE:
                            rangeConjuncts.add(new ComparisonExpression(GREATER_THAN, reference, toExpression(range.getLow().getValue(), type)));
                            break;
                        case EXACTLY:
                            rangeConjuncts.add(new ComparisonExpression(GREATER_THAN_OR_EQUAL, reference, toExpression(range.getLow().getValue(),
                                    type)));
                            break;
                        case BELOW:
                            throw new IllegalStateException("Low Marker should never use BELOW bound: " + range);
                        default:
                            throw new AssertionError("Unhandled bound: " + range.getLow().getBound());
                    }
                }
                if (!range.getHigh().isUpperUnbounded()) {
                    switch (range.getHigh().getBound()) {
                        case ABOVE:
                            throw new IllegalStateException("High Marker should never use ABOVE bound: " + range);
                        case EXACTLY:
                            rangeConjuncts.add(new ComparisonExpression(LESS_THAN_OR_EQUAL, reference, toExpression(range.getHigh().getValue(), type)));
                            break;
                        case BELOW:
                            rangeConjuncts.add(new ComparisonExpression(LESS_THAN, reference, toExpression(range.getHigh().getValue(), type)));
                            break;
                        default:
                            throw new AssertionError("Unhandled bound: " + range.getHigh().getBound());
                    }
                }
                // If rangeConjuncts is null, then the range was ALL, which should already have been checked for
                checkState(!rangeConjuncts.isEmpty());
                disjuncts.add(combineConjuncts(rangeConjuncts));
            }
        }

        // Add back all of the possible single values either as an equality or an IN predicate
        if (singleValues.size() == 1) {
            disjuncts.add(new ComparisonExpression(EQUAL, reference, getOnlyElement(singleValues)));
        }
        else if (singleValues.size() > 1) {
            disjuncts.add(new InPredicate(reference, new InListExpression(singleValues)));
        }
        return disjuncts;
    }

    private static List<Expression> extractDisjuncts(Type type, DiscreteValues discreteValues, QualifiedNameReference reference)
    {
        List<Expression> values = discreteValues.getValues().stream()
                .map(object -> toExpression(object, type))
                .collect(toList());

        // If values is empty, then the equatableValues was either ALL or NONE, both of which should already have been checked for
        checkState(!values.isEmpty());

        Expression predicate;
        if (values.size() == 1) {
            predicate = new ComparisonExpression(EQUAL, reference, getOnlyElement(values));
        }
        else {
            predicate = new InPredicate(reference, new InListExpression(values));
        }

        if (!discreteValues.isWhiteList()) {
            predicate = new NotExpression(predicate);
        }
        return ImmutableList.of(predicate);
    }

    private static boolean isBetween(Range range)
    {
        return !range.getLow().isLowerUnbounded() && range.getLow().getBound() == Marker.Bound.EXACTLY
                && !range.getHigh().isUpperUnbounded() && range.getHigh().getBound() == Marker.Bound.EXACTLY;
    }

    /**
     * Convert an Expression predicate into an ExtractionResult consisting of:
     * 1) A successfully extracted TupleDomain
     * 2) An Expression fragment which represents the part of the original Expression that will need to be re-evaluated
     * after filtering with the TupleDomain.
     */
    public static ExtractionResult fromPredicate(
            Metadata metadata,
            Session session,
            Expression predicate,
            Map<Symbol, Type> types)
    {
        return new Visitor(metadata, session, types).process(predicate, false);
    }

    private static class Visitor
            extends AstVisitor<ExtractionResult, Boolean>
    {
        private final Metadata metadata;
        private final Session session;
        private final Map<Symbol, Type> types;

        private Visitor(Metadata metadata, Session session, Map<Symbol, Type> types)
        {
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.session = requireNonNull(session, "session is null");
            this.types = ImmutableMap.copyOf(requireNonNull(types, "types is null"));
        }

        private Type checkedTypeLookup(Symbol symbol)
        {
            Type type = types.get(symbol);
            checkArgument(type != null, "Types is missing info for symbol: %s", symbol);
            return type;
        }

        private static ValueSet complementIfNecessary(ValueSet valueSet, boolean complement)
        {
            return complement ? valueSet.complement() : valueSet;
        }

        private static Domain complementIfNecessary(Domain domain, boolean complement)
        {
            return complement ? domain.complement() : domain;
        }

        private static Expression complementIfNecessary(Expression expression, boolean complement)
        {
            return complement ? new NotExpression(expression) : expression;
        }

        @Override
        protected ExtractionResult visitExpression(Expression node, Boolean complement)
        {
            // If we don't know how to process this node, the default response is to say that the TupleDomain is "all"
            return new ExtractionResult(TupleDomain.all(), complementIfNecessary(node, complement));
        }

        @Override
        protected ExtractionResult visitLogicalBinaryExpression(LogicalBinaryExpression node, Boolean complement)
        {
            ExtractionResult leftResult = process(node.getLeft(), complement);
            ExtractionResult rightResult = process(node.getRight(), complement);

            TupleDomain<Symbol> leftTupleDomain = leftResult.getTupleDomain();
            TupleDomain<Symbol> rightTupleDomain = rightResult.getTupleDomain();

            LogicalBinaryExpression.Type type = complement ? flipLogicalBinaryType(node.getType()) : node.getType();
            switch (type) {
                case AND:
                    return new ExtractionResult(
                            leftTupleDomain.intersect(rightTupleDomain),
                            combineConjuncts(leftResult.getRemainingExpression(), rightResult.getRemainingExpression()));

                case OR:
                    TupleDomain<Symbol> columnUnionedTupleDomain = TupleDomain.columnWiseUnion(leftTupleDomain, rightTupleDomain);

                    // In most cases, the columnUnionedTupleDomain is only a superset of the actual strict union
                    // and so we can return the current node as the remainingExpression so that all bounds will be double checked again at execution time.
                    Expression remainingExpression = complementIfNecessary(node, complement);

                    // However, there are a few cases where the column-wise union is actually equivalent to the strict union, so we if can detect
                    // some of these cases, we won't have to double check the bounds unnecessarily at execution time.

                    // We can only make inferences if the remaining expressions on both side are equal and deterministic
                    if (leftResult.getRemainingExpression().equals(rightResult.getRemainingExpression()) &&
                            DeterminismEvaluator.isDeterministic(leftResult.getRemainingExpression())) {
                        // The column-wise union is equivalent to the strict union if
                        // 1) If both TupleDomains consist of the same exact single column (e.g. left TupleDomain => (a > 0), right TupleDomain => (a < 10))
                        // 2) If one TupleDomain is a superset of the other (e.g. left TupleDomain => (a > 0, b > 0 && b < 10), right TupleDomain => (a > 5, b = 5))
                        boolean matchingSingleSymbolDomains = !leftTupleDomain.isNone()
                                && !rightTupleDomain.isNone()
                                && leftTupleDomain.getDomains().get().size() == 1
                                && rightTupleDomain.getDomains().get().size() == 1
                                && leftTupleDomain.getDomains().get().keySet().equals(rightTupleDomain.getDomains().get().keySet());
                        boolean oneSideIsSuperSet = leftTupleDomain.contains(rightTupleDomain) || rightTupleDomain.contains(leftTupleDomain);

                        if (matchingSingleSymbolDomains || oneSideIsSuperSet) {
                            remainingExpression = leftResult.getRemainingExpression();
                        }
                    }

                    return new ExtractionResult(columnUnionedTupleDomain, remainingExpression);

                default:
                    throw new AssertionError("Unknown type: " + node.getType());
            }
        }

        private static LogicalBinaryExpression.Type flipLogicalBinaryType(LogicalBinaryExpression.Type type)
        {
            switch (type) {
                case AND:
                    return LogicalBinaryExpression.Type.OR;
                case OR:
                    return LogicalBinaryExpression.Type.AND;
                default:
                    throw new AssertionError("Unknown type: " + type);
            }
        }

        @Override
        protected ExtractionResult visitNotExpression(NotExpression node, Boolean complement)
        {
            return process(node.getValue(), !complement);
        }

        @Override
        protected ExtractionResult visitComparisonExpression(ComparisonExpression node, Boolean complement)
        {
            Optional<NormalizedSimpleComparison> optionalNormalized = toNormalizedSimpleComparison(session, metadata, types, node);
            if (!optionalNormalized.isPresent()) {
                return super.visitComparisonExpression(node, complement);
            }
            NormalizedSimpleComparison normalized = optionalNormalized.get();

            Symbol symbol = Symbol.fromQualifiedName(normalized.getNameReference().getName());
            Type fieldType = checkedTypeLookup(symbol);
            NullableValue value = normalized.getValue();

            // when the field is BIGINT and the value is DOUBLE, transform the expression in such a way that
            // the semantics are preserved while doing the comparisons in terms of double
            // TODO: figure out a way to generalize this for other types
            if (value.getType().equals(DOUBLE) && fieldType.equals(BIGINT)) {
                return process(coerceDoubleToLongComparison(normalized), complement);
            }

            if (!TypeRegistry.canCoerce(value.getType(), fieldType)) {
                return super.visitComparisonExpression(node, complement);
            }

            Object coerced = new FunctionInvoker(metadata.getFunctionRegistry())
                    .invoke(metadata.getFunctionRegistry().getCoercion(value.getType(), fieldType), session.toConnectorSession(), value.getValue());

            return createComparisonExtractionResult(normalized.getComparisonType(), symbol, fieldType, coerced, complement);
        }

        private ExtractionResult createComparisonExtractionResult(ComparisonExpression.Type comparisonType, Symbol column, Type type, @Nullable Object value, boolean complement)
        {
            if (value == null) {
                switch (comparisonType) {
                    case EQUAL:
                    case GREATER_THAN:
                    case GREATER_THAN_OR_EQUAL:
                    case LESS_THAN:
                    case LESS_THAN_OR_EQUAL:
                    case NOT_EQUAL:
                        return new ExtractionResult(TupleDomain.none(), TRUE_LITERAL);

                    case IS_DISTINCT_FROM:
                        Domain domain = complementIfNecessary(Domain.notNull(type), complement);
                        return new ExtractionResult(
                                TupleDomain.withColumnDomains(ImmutableMap.of(column, domain)),
                                TRUE_LITERAL);

                    default:
                        throw new AssertionError("Unhandled type: " + comparisonType);
                }
            }

            Domain domain;
            if (type.isOrderable()) {
                domain = extractOrderableDomain(comparisonType, type, value, complement);
            }
            else if (type.isComparable()) {
                domain = extractEquatableDomain(comparisonType, type, value, complement);
            }
            else {
                throw new AssertionError("Type cannot be used in a comparison expression (should have been caught in analysis): " + type);
            }

            return new ExtractionResult(
                    TupleDomain.withColumnDomains(ImmutableMap.of(column, domain)),
                    TRUE_LITERAL);
        }

        private static Domain extractOrderableDomain(ComparisonExpression.Type comparisonType, Type type, Object value, boolean complement)
        {
            checkArgument(value != null);
            switch (comparisonType) {
                case EQUAL:
                    return Domain.create(complementIfNecessary(ValueSet.ofRanges(Range.equal(type, value)), complement), false);
                case GREATER_THAN:
                    return Domain.create(complementIfNecessary(ValueSet.ofRanges(Range.greaterThan(type, value)), complement), false);
                case GREATER_THAN_OR_EQUAL:
                    return Domain.create(complementIfNecessary(ValueSet.ofRanges(Range.greaterThanOrEqual(type, value)), complement), false);
                case LESS_THAN:
                    return Domain.create(complementIfNecessary(ValueSet.ofRanges(Range.lessThan(type, value)), complement), false);
                case LESS_THAN_OR_EQUAL:
                    return Domain.create(complementIfNecessary(ValueSet.ofRanges(Range.lessThanOrEqual(type, value)), complement), false);
                case NOT_EQUAL:
                    return Domain.create(complementIfNecessary(ValueSet.ofRanges(Range.lessThan(type, value), Range.greaterThan(type, value)), complement), false);
                case IS_DISTINCT_FROM:
                    // Need to potential complement the whole domain for IS_DISTINCT_FROM since it is null-aware
                    return complementIfNecessary(Domain.create(ValueSet.ofRanges(Range.lessThan(type, value), Range.greaterThan(type, value)), true), complement);
                default:
                    throw new AssertionError("Unhandled type: " + comparisonType);
            }
        }

        private static Domain extractEquatableDomain(ComparisonExpression.Type comparisonType, Type type, Object value, boolean complement)
        {
            checkArgument(value != null);
            switch (comparisonType) {
                case EQUAL:
                    return Domain.create(complementIfNecessary(ValueSet.of(type, value), complement), false);
                case NOT_EQUAL:
                    return Domain.create(complementIfNecessary(ValueSet.of(type, value).complement(), complement), false);
                case IS_DISTINCT_FROM:
                    // Need to potential complement the whole domain for IS_DISTINCT_FROM since it is null-aware
                    return complementIfNecessary(Domain.create(ValueSet.of(type, value).complement(), true), complement);
                default:
                    throw new AssertionError("Unhandled type: " + comparisonType);
            }
        }

        @Override
        protected ExtractionResult visitInPredicate(InPredicate node, Boolean complement)
        {
            if (!(node.getValue() instanceof QualifiedNameReference) || !(node.getValueList() instanceof InListExpression)) {
                return super.visitInPredicate(node, complement);
            }

            InListExpression valueList = (InListExpression) node.getValueList();
            checkState(!valueList.getValues().isEmpty(), "InListExpression should never be empty");

            ImmutableList.Builder<Expression> disjuncts = ImmutableList.builder();
            for (Expression expression : valueList.getValues()) {
                disjuncts.add(new ComparisonExpression(EQUAL, node.getValue(), expression));
            }
            return process(or(disjuncts.build()), complement);
        }

        @Override
        protected ExtractionResult visitBetweenPredicate(BetweenPredicate node, Boolean complement)
        {
            // Re-write as two comparison expressions
            return process(and(
                    new ComparisonExpression(GREATER_THAN_OR_EQUAL, node.getValue(), node.getMin()),
                    new ComparisonExpression(LESS_THAN_OR_EQUAL, node.getValue(), node.getMax())), complement);
        }

        @Override
        protected ExtractionResult visitIsNullPredicate(IsNullPredicate node, Boolean complement)
        {
            if (!(node.getValue() instanceof QualifiedNameReference)) {
                return super.visitIsNullPredicate(node, complement);
            }

            Symbol symbol = Symbol.fromQualifiedName(((QualifiedNameReference) node.getValue()).getName());
            Type columnType = checkedTypeLookup(symbol);
            Domain domain = complementIfNecessary(Domain.onlyNull(columnType), complement);
            return new ExtractionResult(
                    TupleDomain.withColumnDomains(ImmutableMap.of(symbol, domain)),
                    TRUE_LITERAL);
        }

        @Override
        protected ExtractionResult visitIsNotNullPredicate(IsNotNullPredicate node, Boolean complement)
        {
            if (!(node.getValue() instanceof QualifiedNameReference)) {
                return super.visitIsNotNullPredicate(node, complement);
            }

            Symbol symbol = Symbol.fromQualifiedName(((QualifiedNameReference) node.getValue()).getName());
            Type columnType = checkedTypeLookup(symbol);

            Domain domain = complementIfNecessary(Domain.notNull(columnType), complement);
            return new ExtractionResult(
                    TupleDomain.withColumnDomains(ImmutableMap.of(symbol, domain)),
                    TRUE_LITERAL);
        }

        @Override
        protected ExtractionResult visitBooleanLiteral(BooleanLiteral node, Boolean complement)
        {
            boolean value = complement ? !node.getValue() : node.getValue();
            return new ExtractionResult(value ? TupleDomain.all() : TupleDomain.none(), TRUE_LITERAL);
        }

        @Override
        protected ExtractionResult visitNullLiteral(NullLiteral node, Boolean complement)
        {
            return new ExtractionResult(TupleDomain.none(), TRUE_LITERAL);
        }
    }

    /**
     * Extract a normalized simple comparison between a QualifiedNameReference and a native value if possible.
     */
    private static Optional<NormalizedSimpleComparison> toNormalizedSimpleComparison(Session session, Metadata metadata, Map<Symbol, Type> types, ComparisonExpression comparison)
    {
        IdentityHashMap<Expression, Type> expressionTypes = ExpressionAnalyzer.getExpressionTypes(session, metadata, new SqlParser(), types, comparison);
        Object left = ExpressionInterpreter.expressionOptimizer(comparison.getLeft(), metadata, session, expressionTypes).optimize(NoOpSymbolResolver.INSTANCE);
        Object right = ExpressionInterpreter.expressionOptimizer(comparison.getRight(), metadata, session, expressionTypes).optimize(NoOpSymbolResolver.INSTANCE);

        if (left instanceof QualifiedNameReference && !(right instanceof Expression)) {
            return Optional.of(new NormalizedSimpleComparison((QualifiedNameReference) left, comparison.getType(), new NullableValue(expressionTypes.get(comparison.getRight()), right)));
        }
        if (right instanceof QualifiedNameReference && !(left instanceof Expression)) {
            return Optional.of(new NormalizedSimpleComparison((QualifiedNameReference) right, comparison.getType().flip(), new NullableValue(expressionTypes.get(comparison.getLeft()), left)));
        }
        return Optional.empty();
    }

    private static class NormalizedSimpleComparison
    {
        private final QualifiedNameReference nameReference;
        private final ComparisonExpression.Type comparisonType;
        private final NullableValue value;

        public NormalizedSimpleComparison(QualifiedNameReference nameReference, ComparisonExpression.Type comparisonType, NullableValue value)
        {
            this.nameReference = requireNonNull(nameReference, "nameReference is null");
            this.comparisonType = requireNonNull(comparisonType, "comparisonType is null");
            this.value = requireNonNull(value, "value is null");
        }

        public QualifiedNameReference getNameReference()
        {
            return nameReference;
        }

        public ComparisonExpression.Type getComparisonType()
        {
            return comparisonType;
        }

        public NullableValue getValue()
        {
            return value;
        }
    }

    private static Expression coerceDoubleToLongComparison(NormalizedSimpleComparison normalized)
    {
        checkArgument(normalized.getValue().getType().equals(DOUBLE), "Value should be of DOUBLE type");
        checkArgument(!normalized.getValue().isNull(), "Value should not be null");
        QualifiedNameReference reference = normalized.getNameReference();
        Double value = (Double) normalized.getValue().getValue();

        switch (normalized.getComparisonType()) {
            case GREATER_THAN_OR_EQUAL:
            case LESS_THAN:
                return new ComparisonExpression(normalized.getComparisonType(), reference, toExpression(DoubleMath.roundToLong(value, CEILING), BIGINT));

            case GREATER_THAN:
            case LESS_THAN_OR_EQUAL:
                return new ComparisonExpression(normalized.getComparisonType(), reference, toExpression(DoubleMath.roundToLong(value, FLOOR), BIGINT));

            case EQUAL:
                Long equalValue = DoubleMath.roundToLong(value, FLOOR);
                if (equalValue.doubleValue() != value) {
                    // Return something that is false for all non-null values
                    return and(new ComparisonExpression(EQUAL, reference, new LongLiteral("0")),
                            new ComparisonExpression(NOT_EQUAL, reference, new LongLiteral("0")));
                }
                return new ComparisonExpression(normalized.getComparisonType(), reference, toExpression(equalValue, BIGINT));

            case NOT_EQUAL:
                Long notEqualValue = DoubleMath.roundToLong(value, FLOOR);
                if (notEqualValue.doubleValue() != value) {
                    // Return something that is true for all non-null values
                    return or(new ComparisonExpression(EQUAL, reference, new LongLiteral("0")),
                            new ComparisonExpression(NOT_EQUAL, reference, new LongLiteral("0")));
                }
                return new ComparisonExpression(normalized.getComparisonType(), reference, toExpression(notEqualValue, BIGINT));

            case IS_DISTINCT_FROM:
                Long distinctValue = DoubleMath.roundToLong(value, FLOOR);
                if (distinctValue.doubleValue() != value) {
                    return TRUE_LITERAL;
                }
                return new ComparisonExpression(normalized.getComparisonType(), reference, toExpression(distinctValue, BIGINT));

            default:
                throw new AssertionError("Unhandled type: " + normalized.getComparisonType());
        }
    }

    public static class ExtractionResult
    {
        private final TupleDomain<Symbol> tupleDomain;
        private final Expression remainingExpression;

        public ExtractionResult(TupleDomain<Symbol> tupleDomain, Expression remainingExpression)
        {
            this.tupleDomain = requireNonNull(tupleDomain, "tupleDomain is null");
            this.remainingExpression = requireNonNull(remainingExpression, "remainingExpression is null");
        }

        public TupleDomain<Symbol> getTupleDomain()
        {
            return tupleDomain;
        }

        public Expression getRemainingExpression()
        {
            return remainingExpression;
        }
    }
}
