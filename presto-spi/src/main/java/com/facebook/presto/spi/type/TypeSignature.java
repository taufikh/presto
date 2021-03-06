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
package com.facebook.presto.spi.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public class TypeSignature
{
    private final String base;
    private final List<TypeSignatureParameter> parameters;
    private final boolean calculated;

    public TypeSignature(String base, List<TypeSignatureParameter> parameters)
    {
        checkArgument(base != null, "base is null");
        this.base = base;
        checkArgument(!base.isEmpty(), "base is empty");
        checkArgument(validateName(base), "Bad characters in base type: %s", base);
        checkArgument(parameters != null, "parameters is null");
        this.parameters = unmodifiableList(new ArrayList<>(parameters));

        this.calculated = parameters.stream().anyMatch(TypeSignatureParameter::isCalculated);
    }

    // TODO: merge literalParameters for Row with TypeSignatureParameter
    @Deprecated
    public TypeSignature(String base, List<TypeSignature> typeSignatureParameters, List<String> literalParameters)
    {
        this(base, createNamedTypeParameters(typeSignatureParameters, literalParameters));
    }

    public TypeSignature bindParameters(Map<String, Type> boundParameters)
    {
        if (boundParameters.containsKey(base)) {
            if (!getParameters().isEmpty()) {
                throw new IllegalStateException("Type parameters cannot have parameters");
            }
            return boundParameters.get(base).getTypeSignature();
        }

        List<TypeSignatureParameter> parameters = getParameters().stream()
                .map(signature -> signature.bindParameters(boundParameters))
                .collect(toList());
        return new TypeSignature(base, parameters);
    }

    public String getBase()
    {
        return base;
    }

    public List<TypeSignatureParameter> getParameters()
    {
        return parameters;
    }

    public List<TypeSignature> getTypeParametersAsTypeSignatures()
    {
        List<TypeSignature> result = new ArrayList<>();
        for (TypeSignatureParameter parameter : parameters) {
            if (parameter.getKind() != ParameterKind.TYPE) {
                throw new IllegalStateException(
                        format("Expected all parameters to be TypeSignatures but [%s] was found", parameter.toString()));
            }
            result.add(parameter.getTypeSignature());
        }
        return result;
    }

    public boolean isCalculated()
    {
        return calculated;
    }

    @JsonCreator
    public static TypeSignature parseTypeSignature(String signature)
    {
        return parseTypeSignature(signature, new HashSet<>());
    }

    public static TypeSignature parseTypeSignature(String signature, Set<String> literalCalculationParameters)
    {
        if (!signature.contains("<") && !signature.contains("(")) {
            return new TypeSignature(signature, new ArrayList<>());
        }
        if (signature.toLowerCase(Locale.ENGLISH).startsWith(StandardTypes.ROW + "<")) {
            return parseRowTypeSignature(signature);
        }

        String baseName = null;
        List<TypeSignatureParameter> parameters = new ArrayList<>();
        int parameterStart = -1;
        int bracketCount = 0;

        for (int i = 0; i < signature.length(); i++) {
            char c = signature.charAt(i);
            // TODO: remove angle brackets support once ROW<TYPE>(name) will be dropped
            // Angle brackets here are checked not for the support of ARRAY<> and MAP<>
            // but to correctly parse ARRAY(row<BIGINT, BIGINT>('a','b'))
            if (c == '(' || c == '<') {
                // hack for min/max
                if (bracketCount == 0 && (baseName == null || !baseName.equals("min") && !baseName.equals("max"))) {
                    verify(baseName == null, "Expected baseName to be null");
                    verify(parameterStart == -1, "Expected parameter start to be -1");
                    baseName = signature.substring(0, i);
                    parameterStart = i + 1;
                }
                bracketCount++;
            }
            else if (c == ')' || c == '>') {
                bracketCount--;
                checkArgument(bracketCount >= 0, "Bad type signature: '%s'", signature);
                if (bracketCount == 0) {
                    checkArgument(parameterStart >= 0, "Bad type signature: '%s'", signature);
                    parameters.add(parseTypeSignatureParameter(signature, parameterStart, i, literalCalculationParameters));
                    parameterStart = i + 1;
                    if (i == signature.length() - 1) {
                        return new TypeSignature(baseName, parameters);
                    }
                }
            }
            else if (c == ',') {
                if (bracketCount == 1) {
                    checkArgument(parameterStart >= 0, "Bad type signature: '%s'", signature);
                    parameters.add(parseTypeSignatureParameter(signature, parameterStart, i, literalCalculationParameters));
                    parameterStart = i + 1;
                }
            }
        }

        throw new IllegalArgumentException(format("Bad type signature: '%s'", signature));
    }

    @Deprecated
    private static TypeSignature parseRowTypeSignature(String signature)
    {
        String baseName = null;
        List<TypeSignature> parameters = new ArrayList<>();
        List<String> fieldNames = new ArrayList<>();
        int parameterStart = -1;
        int bracketCount = 0;
        boolean inLiteralParameters = false;

        for (int i = 0; i < signature.length(); i++) {
            char c = signature.charAt(i);
            if (c == '<') {
                if (bracketCount == 0) {
                    verify(baseName == null, "Expected baseName to be null");
                    verify(parameterStart == -1, "Expected parameter start to be -1");
                    baseName = signature.substring(0, i);
                    parameterStart = i + 1;
                }
                bracketCount++;
            }
            else if (c == '>') {
                bracketCount--;
                checkArgument(bracketCount >= 0, "Bad type signature: '%s'", signature);
                if (bracketCount == 0) {
                    checkArgument(parameterStart >= 0, "Bad type signature: '%s'", signature);
                    parameters.add(parseTypeSignature(signature.substring(parameterStart, i)));
                    parameterStart = i + 1;
                    verify(i < signature.length() - 1, "Row's signature can not end with angle bracket");
                }
            }
            else if (c == ',') {
                if (bracketCount == 1) {
                    if (!inLiteralParameters) {
                        checkArgument(parameterStart >= 0, "Bad type signature: '%s'", signature);
                        parameters.add(parseTypeSignature(signature.substring(parameterStart, i)));
                        parameterStart = i + 1;
                    }
                    else {
                        checkArgument(parameterStart >= 0, "Bad type signature: '%s'", signature);
                        fieldNames.add(parseFieldName(signature.substring(parameterStart, i)));
                        parameterStart = i + 1;
                    }
                }
            }
            else if (c == '(') {
                if (bracketCount == 0) {
                    inLiteralParameters = true;
                    if (baseName == null) {
                        verify(parameters.isEmpty(), "Expected no parameters");
                        verify(parameterStart == -1, "Expected parameter start to be -1");
                        baseName = signature.substring(0, i);
                    }
                    parameterStart = i + 1;
                }
                bracketCount++;
            }
            else if (c == ')') {
                bracketCount--;
                if (bracketCount == 0) {
                    checkArgument(inLiteralParameters, "Bad type signature: '%s'", signature);
                    inLiteralParameters = false;
                    checkArgument(i == signature.length() - 1, "Bad type signature: '%s'", signature);
                    checkArgument(parameterStart >= 0, "Bad type signature: '%s'", signature);
                    fieldNames.add(parseFieldName(signature.substring(parameterStart, i)));
                    return new TypeSignature(baseName, createNamedTypeParameters(parameters, fieldNames));
                }
            }
        }

        throw new IllegalArgumentException(format("Bad type signature: '%s'", signature));
    }

    private static List<TypeSignatureParameter> createNamedTypeParameters(List<TypeSignature> parameters, List<String> fieldNames)
    {
        requireNonNull(parameters, "parameters is null");
        requireNonNull(fieldNames, "fieldNames is null");
        verify(parameters.size() == fieldNames.size() || fieldNames.isEmpty(), "Number of parameters and fieldNames for ROW type doesn't match");
        List<TypeSignatureParameter> result = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            String fieldName = fieldNames.isEmpty() ? format("field%d", i) : fieldNames.get(i);
            result.add(TypeSignatureParameter.of(new NamedTypeSignature(fieldName, parameters.get(i))));
        }
        return result;
    }

    private static TypeSignatureParameter parseTypeSignatureParameter(
            String signature,
            int begin,
            int end,
            Set<String> literalCalculationParameters)
    {
        String parameterName = signature.substring(begin, end);
        if (Character.isDigit(signature.charAt(begin))) {
            return TypeSignatureParameter.of(Long.parseLong(parameterName));
        }
        else if (literalCalculationParameters.contains(parameterName)) {
            return TypeSignatureParameter.of(new TypeLiteralCalculation(parameterName));
        }
        else {
            return TypeSignatureParameter.of(parseTypeSignature(parameterName, literalCalculationParameters));
        }
    }

    private static String parseFieldName(String fieldName)
    {
        checkArgument(fieldName != null && fieldName.length() >= 2, "Bad fieldName: '%s'", fieldName);
        checkArgument(fieldName.startsWith("'") && fieldName.endsWith("'"), "Bad fieldName: '%s'", fieldName);
        return fieldName.substring(1, fieldName.length() - 1);
    }

    @Override
    @JsonValue
    public String toString()
    {
        if (base.equalsIgnoreCase(StandardTypes.ROW)) {
            return rowToString();
        }
        else {
            StringBuilder typeName = new StringBuilder(base);
            if (!parameters.isEmpty()) {
                typeName.append("(");
                boolean first = true;
                for (TypeSignatureParameter parameter : parameters) {
                    if (!first) {
                        typeName.append(",");
                    }
                    first = false;
                    typeName.append(parameter.toString());
                }
                typeName.append(")");
            }
            return typeName.toString();
        }
    }

    @Deprecated
    private String rowToString()
    {
        verify(parameters.stream().allMatch(parameter -> parameter.getKind() == ParameterKind.NAMED_TYPE),
                format("Incorrect parameters for row type %s", parameters));

        String types = parameters.stream()
                .map(TypeSignatureParameter::getNamedTypeSignature)
                .map(NamedTypeSignature::getTypeSignature)
                .map(TypeSignature::toString)
                .collect(Collectors.joining(","));

        String fieldNames = parameters.stream()
                .map(TypeSignatureParameter::getNamedTypeSignature)
                .map(NamedTypeSignature::getName)
                .map(name -> format("'%s'", name))
                .collect(Collectors.joining(","));

        return format("row<%s>(%s)", types, fieldNames);
    }

    private static void checkArgument(boolean argument, String format, Object... args)
    {
        if (!argument) {
            throw new IllegalArgumentException(format(format, args));
        }
    }

    private static void verify(boolean argument, String message)
    {
        if (!argument) {
            throw new AssertionError(message);
        }
    }

    private static boolean validateName(String name)
    {
        return name.chars().noneMatch(c -> c == '<' || c == '>' || c == ',');
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        TypeSignature other = (TypeSignature) o;

        return Objects.equals(this.base.toLowerCase(Locale.ENGLISH), other.base.toLowerCase(Locale.ENGLISH)) &&
                Objects.equals(this.parameters, other.parameters);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(base.toLowerCase(Locale.ENGLISH), parameters);
    }
}
