/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.query.ast;

import org.apache.jackrabbit.oak.commons.PathUtils;
import org.apache.jackrabbit.oak.query.index.FilterImpl;
import org.apache.jackrabbit.oak.spi.query.Filter;

/**
 * The "isdescendantnode(...)" join condition.
 */
public class DescendantNodeJoinConditionImpl extends JoinConditionImpl {

    private final String descendantSelectorName;
    private final String ancestorSelectorName;
    private SelectorImpl descendantSelector;
    private SelectorImpl ancestorSelector;

    public DescendantNodeJoinConditionImpl(String descendantSelectorName,
            String ancestorSelectorName) {
        this.descendantSelectorName = descendantSelectorName;
        this.ancestorSelectorName = ancestorSelectorName;
    }

    @Override
    boolean accept(AstVisitor v) {
        return v.visit(this);
    }

    @Override
    public String toString() {
        return "isdescendantnode(" + 
                quote(descendantSelectorName) + 
                ", " + quote(ancestorSelectorName) + ')';
    }

    public void bindSelector(SourceImpl source) {
        descendantSelector = source.getExistingSelector(descendantSelectorName);
        ancestorSelector = source.getExistingSelector(ancestorSelectorName);
    }

    @Override
    public boolean evaluate() {
        String a = ancestorSelector.currentPath();
        String d = descendantSelector.currentPath();
        return PathUtils.isAncestor(a, d);
    }

    @Override
    public void restrict(FilterImpl f) {
        if (f.getSelector() == ancestorSelector) {
            String d = descendantSelector.currentPath();
            if (d == null && f.isPreparing() && descendantSelector.isPrepared()) {
                // during the prepare phase, if the selector is already
                // prepared, then we would know the value
                d = KNOWN_PATH;
            }
            if (d != null) {
                f.restrictPath(PathUtils.getParentPath(d), Filter.PathRestriction.PARENT);
            }
        }
        if (f.getSelector() == descendantSelector) {
            String a = ancestorSelector.currentPath();
            if (a == null && f.isPreparing() && ancestorSelector.isPrepared()) {
                // during the prepare phase, if the selector is already
                // prepared, then we would know the value
                a = KNOWN_PATH;
            }
            if (a != null) {
                f.restrictPath(a, Filter.PathRestriction.ALL_CHILDREN);
            }
        }
    }

    @Override
    public void restrictPushDown(SelectorImpl s) {
        // nothing to do
    }

    @Override
    public boolean isParent(SourceImpl source) {
        return source == ancestorSelector;
    }

}
