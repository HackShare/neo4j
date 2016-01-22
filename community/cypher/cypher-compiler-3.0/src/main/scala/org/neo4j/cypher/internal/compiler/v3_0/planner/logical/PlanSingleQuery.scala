/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v3_0.planner.logical

import org.neo4j.cypher.internal.compiler.v3_0.planner.PlannerQuery
import org.neo4j.cypher.internal.compiler.v3_0.planner.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.compiler.v3_0.planner.logical.steps.{countStorePlanner, verifyBestPlan}
import org.neo4j.cypher.internal.frontend.v3_0.Rewriter

/*
This coordinates PlannerQuery planning and delegates work to the classes that do the actual planning of
QueryGraphs and EventHorizons
 */
case class PlanSingleQuery(planPart: (PlannerQuery, LogicalPlanningContext, Option[LogicalPlan]) => LogicalPlan = planPart,
                           planEventHorizon: LogicalPlanningFunction2[PlannerQuery, LogicalPlan, LogicalPlan] = PlanEventHorizon,
                           expressionRewriterFactory: (LogicalPlanningContext => Rewriter) = ExpressionRewriterFactory,
                           planWithTail: LogicalPlanningFunction2[LogicalPlan, Option[PlannerQuery], LogicalPlan] = PlanWithTail(),
                           planUpdates: LogicalPlanningFunction2[PlannerQuery, LogicalPlan, LogicalPlan] = PlanUpdates) extends LogicalPlanningFunction1[PlannerQuery, LogicalPlan] {

  override def apply(in: PlannerQuery)(implicit context: LogicalPlanningContext): LogicalPlan = {
    val partPlan = countStorePlanner(in).getOrElse(planPart(in, context, None))
   //always use eager if configured to do so
    val alwaysEager = context.config.updateStrategy.alwaysEager

    // TODO: Pass planEffects as a LogicalPlanningFunction? (Default PlanEffects, alwaysEager could be another implementation)
    val planWithEffect =
      if (alwaysEager || Eagerness.conflictInHead(partPlan, in))
        context.logicalPlanProducer.planEager(partPlan)
      else partPlan
    val planWithUpdates = planUpdates(in, planWithEffect)(context)
    val projectedPlan = planEventHorizon(in, planWithUpdates)
    val projectedContext = context.recurse(projectedPlan)
    val expressionRewriter = expressionRewriterFactory(projectedContext)
    val completePlan = projectedPlan.endoRewrite(expressionRewriter)

    val finalPlan = planWithTail(completePlan, in.tail)(projectedContext)
    verifyBestPlan(finalPlan, in)
  }
}