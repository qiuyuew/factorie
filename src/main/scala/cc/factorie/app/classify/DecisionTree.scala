/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package cc.factorie.app.classify

import cc.factorie._
import cc.factorie.optimize._
import la.{DenseTensor1, Tensor}

class ID3DecisionTreeTrainer extends ClassifierTrainer {
  var iterations = 10
  var learningRateDecay = 0.9
  def train[L <: LabeledMutableDiscreteVar[_], F <: DiscreteTensorVar](il: LabelList[L, F]): ModelBasedClassifier[L, Model] = {
    val dmodel = new ID3DecisionTreeTemplate[L, F](
      il.labelToFeatures,
      il.labelDomain,
      il.instanceDomain)(il.labelManifest, il.featureManifest)
    val instanceWeights = il.map(il.instanceWeight(_)).toArray
    dmodel.train(il, new DenseTensor1(instanceWeights))
    new ModelBasedClassifier(dmodel, il.head.domain)
  }
}

class AdaBoostDecisionStumpTrainer extends ClassifierTrainer {
  var iterations = 10
  def train[L <: LabeledMutableDiscreteVar[_], F <: DiscreteTensorVar](il: LabelList[L, F]): ModelBasedClassifier[L, Model] = {
    // FIXME why can't we make AdaBoost template type more general?
    val dmodel = new AdaBoostDecisionStumpTemplate[L, F](
      il.labelToFeatures,
      il.labelDomain,
      il.instanceDomain)(il.labelManifest, il.featureManifest)
    dmodel.numIterations = iterations
    dmodel.train(il)
    new ModelBasedClassifier(dmodel, il.head.domain)
  }
}