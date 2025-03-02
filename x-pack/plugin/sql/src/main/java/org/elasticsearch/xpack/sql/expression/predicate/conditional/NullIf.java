/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.sql.expression.predicate.conditional;

import org.elasticsearch.xpack.ql.expression.Expression;
import org.elasticsearch.xpack.ql.expression.Expressions;
import org.elasticsearch.xpack.ql.expression.gen.pipeline.Pipe;
import org.elasticsearch.xpack.ql.expression.gen.script.ParamsBuilder;
import org.elasticsearch.xpack.ql.expression.gen.script.ScriptTemplate;
import org.elasticsearch.xpack.ql.tree.NodeInfo;
import org.elasticsearch.xpack.ql.tree.Source;

import java.util.Arrays;
import java.util.List;

import static org.elasticsearch.xpack.ql.expression.gen.script.ParamsBuilder.paramsBuilder;

/**
 * Accepts 2 arguments of any data type and returns null if they are equal,
 * and the 1st argument otherwise.
 */
public class NullIf extends ConditionalFunction {

    private final Expression left, right;

    public NullIf(Source source, Expression left, Expression right) {
        super(source, Arrays.asList(left, right));
        this.left = left;
        this.right = right;
    }

    @Override
    protected NodeInfo<? extends NullIf> info() {
        return NodeInfo.create(this, NullIf::new, children().get(0), children().get(1));
    }

    @Override
    public Expression replaceChildren(List<Expression> newChildren) {
        return new NullIf(source(), newChildren.get(0), newChildren.get(1));
    }

    public Expression left() {
        return left;
    }

    public Expression right() {
        return right;
    }

    @Override
    public boolean foldable() {
        return left.semanticEquals(right) || super.foldable();
    }

    @Override
    public Object fold() {
        if (left.semanticEquals(right)) {
            return null;
        }
        return NullIfProcessor.apply(left.fold(), right.fold());
    }

    @Override
    public ScriptTemplate asScript() {
        ScriptTemplate leftScript = asScript(children().get(0));
        ScriptTemplate rightScript = asScript(children().get(1));
        String template = "{sql}.nullif(" + leftScript.template() + "," + rightScript.template() + ")";
        ParamsBuilder params = paramsBuilder();
        params.script(leftScript.params());
        params.script(rightScript.params());

        return new ScriptTemplate(formatTemplate(template), params.build(), dataType);
    }

    @Override
    protected Pipe makePipe() {
        return new NullIfPipe(source(), this, Expressions.pipe(children().get(0)), Expressions.pipe(children().get(1)));
    }
}
