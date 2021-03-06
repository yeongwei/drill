/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.planner.logical;

import java.util.ArrayList;
import java.util.List;

import org.apache.drill.exec.exception.UnsupportedOperatorCollector;
import org.apache.drill.exec.planner.StarColumnHelper;
import org.apache.drill.exec.planner.sql.DrillOperatorTable;
import org.apache.drill.exec.work.foreman.SqlUnsupportedException;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelShuttleImpl;
import org.apache.calcite.rel.logical.LogicalUnion;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlSingleValueAggFunction;
import org.apache.calcite.util.NlsString;

/**
 * This class rewrites all the project expression that contain convert_to/ convert_from
 * to actual implementations.
 * Eg: convert_from(EXPR, 'JSON') is rewritten as convert_fromjson(EXPR)
 *
 * With the actual method name we can find out if the function has a complex
 * output type and we will fire/ ignore certain rules (merge project rule) based on this fact.
 */
public class PreProcessLogicalRel extends RelShuttleImpl {
  private RelDataTypeFactory factory;
  private DrillOperatorTable table;
  private UnsupportedOperatorCollector unsupportedOperatorCollector;

  public static PreProcessLogicalRel createVisitor(RelDataTypeFactory factory, DrillOperatorTable table) {
    return new PreProcessLogicalRel(factory, table);
  }

  private PreProcessLogicalRel(RelDataTypeFactory factory, DrillOperatorTable table) {
    super();
    this.factory = factory;
    this.table = table;
    this.unsupportedOperatorCollector = new UnsupportedOperatorCollector();
  }

  @Override
  public RelNode visit(LogicalAggregate aggregate) {
    for(AggregateCall aggregateCall : aggregate.getAggCallList()) {
      if(aggregateCall.getAggregation() instanceof SqlSingleValueAggFunction) {
        unsupportedOperatorCollector.setException(SqlUnsupportedException.ExceptionType.FUNCTION,
            "Non-scalar sub-query used in an expression\n" +
            "See Apache Drill JIRA: DRILL-1937");
        throw new UnsupportedOperationException();
      }
    }

    return visitChild(aggregate, 0, aggregate.getInput());
  }

  @Override
  public RelNode visit(LogicalProject project) {
    List<RexNode> exprList = new ArrayList<>();
    boolean rewrite = false;

    for (RexNode rex : project.getChildExps()) {
      RexNode newExpr = rex;
      if (rex instanceof RexCall) {
        RexCall function = (RexCall) rex;
        String functionName = function.getOperator().getName();
        int nArgs = function.getOperands().size();

        // check if its a convert_from or convert_to function
        if (functionName.equalsIgnoreCase("convert_from") || functionName.equalsIgnoreCase("convert_to")) {
          assert nArgs == 2 && function.getOperands().get(1) instanceof RexLiteral;
          String literal = ((NlsString) (((RexLiteral) function.getOperands().get(1)).getValue())).getValue();
          RexBuilder builder = new RexBuilder(factory);

          // construct the new function name based on the input argument
          String newFunctionName = functionName + literal;

          // Look up the new function name in the drill operator table
          List<SqlOperator> operatorList = table.getSqlOperator(newFunctionName);
          assert operatorList.size() > 0;
          SqlFunction newFunction = null;

          // Find the SqlFunction with the correct args
          for (SqlOperator op : operatorList) {
            if (op.getOperandTypeChecker().getOperandCountRange().isValidCount(nArgs - 1)) {
              newFunction = (SqlFunction) op;
              break;
            }
          }
          assert newFunction != null;

          // create the new expression to be used in the rewritten project
          newExpr = builder.makeCall(newFunction, function.getOperands().subList(0, 1));
          rewrite = true;
        }
      }
      exprList.add(newExpr);
    }

    if (rewrite == true) {
      LogicalProject newProject = project.copy(project.getTraitSet(), project.getInput(0), exprList, project.getRowType());
      return visitChild(newProject, 0, project.getInput());
    }

    return visitChild(project, 0, project.getInput());
  }

  @Override
  public RelNode visit(LogicalUnion union) {
    for(RelNode child : union.getInputs()) {
      for(RelDataTypeField dataField : child.getRowType().getFieldList()) {
        if(dataField.getName().contains(StarColumnHelper.STAR_COLUMN)) {
          unsupportedOperatorCollector.setException(SqlUnsupportedException.ExceptionType.RELATIONAL,
              "Union-All over schema-less tables must specify the columns explicitly\n" +
              "See Apache Drill JIRA: DRILL-2414");
          throw new UnsupportedOperationException();
        }
      }
    }

    return visitChildren(union);
  }

  public void convertException() throws SqlUnsupportedException {
    unsupportedOperatorCollector.convertException();
  }
}
