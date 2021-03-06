package com.etsy.conjecture.scalding.train

import cascading.pipe.Pipe
import com.twitter.scalding._
import com.etsy.conjecture.data._
import com.etsy.conjecture.model._
import scala.io.Source
import scala.collection.JavaConversions._

class MulticlassModelTrainer(args: Args, categories: Array[String]) extends AbstractModelTrainer[MulticlassLabel, UpdateableMulticlassLinearModel] with ModelTrainerStrategy[MulticlassLabel, UpdateableMulticlassLinearModel] {

    /** 
     * Number of iterations for sequential gradient descent
     */
    val iters = args.getOrElse("iters", "1").toInt

    /** 
     *  What type of linear model should be used?
     *  Options are:
     *  1. perceptron
     *  2. linear_svm
     *  3. logistic_regression
     *  4. mira
     */
    val modelType = args.getOrElse("model", "logistic_regression").toString

    /**
     *  What kind of learning rate schedule / regularization
     *  should we use?
     *
     *  Options:
     *  1. elastic_net
     *  2. adagrad
     *  3. passive_aggressive
     *  4. ftrl
     */
    val optimizerType = args.getOrElse("optimizer", "elastic_net")

    /** Aggressiveness parameter for passive aggressive classifier **/
    val aggressiveness = args.getOrElse("aggressiveness", "2.0").toDouble

    val finalThresholding = args.getOrElse("final_thresholding", "0.0").toDouble

    /**
     * Initial learning rate used for SGD learning.
     */
    val initialLearningRate = args.getOrElse("rate", "0.1").toDouble

   /** Base of the exponential learning rate (e.g., 0.99^{# examples seen}). **/
    val exponentialLearningRateBase = args.getOrElse("exponential_learning_rate_base", "1.0").toDouble

    /** Whether to use the exponential learning rate.  If not chosen then the learning rate is like 1.0 / epoch. **/
    val useExponentialLearningRate = args.boolean("exponential_learning_rate_base")

    /** 
     * A fudge factor so that an "epoch" for the purpose of learning rate computation can be more than one example,
     * in which case the "epoch" will take a fractional amount equal to {# examples seen} / examples_per_epoch.
     */
    val examplesPerEpoch = args.getOrElse("examples_per_epoch", "10000").toDouble

    /**
     * Weight on laplace regularization- a laplace prior on the parameters
     * sparsity inducing ala lasso
     */
    val laplace = args.getOrElse("laplace", "0.0").toDouble

    /**
     * Weight on gaussian prior on the parameters
     * similar to ridge 
     */
    val gauss = args.getOrElse("gauss", "0.0").toDouble

    /** Period of gradient truncation updates **/
    val truncationPeriod = args.getOrElse("period", Int.MaxValue.toString).toInt

    /** 
     * Aggressiveness of gradient truncation updates, how much shrinkage
     * is applied to the model's parameters
     */
    val truncationAlpha = args.getOrElse("alpha", "0.0").toDouble

    /**
     * Threshold for applying gradient truncation updates
     * parameter values smaller than this in magnitude are truncated
     */
    val truncationThresh = args.getOrElse("thresh", "0.0").toDouble

    /**
     *  Learning rate parameters for FTRL
     */
    val ftrlAlpha = args.getOrElse("ftrlAlpha", "1.0").toDouble
    val ftrlBeta = args.getOrElse("ftrlBeta", "1.0").toDouble

    val classSampleProbabilities = args.optional("class_probs")
      .map { entries : String =>
        entries.split(",").map {
          s:String =>
            val p = s.split(":")
          (p(0), p(1).toDouble)
        }.toMap
      }
      .getOrElse(Map[String, Double]())

    val classSampleProbabilityFile = args.optional("class_prob_file")

    // stores sampling rates for different classes
    lazy val probabilityMap : Map[String, Double] = {
      val probs = categories.map{ c:String => (c, classSampleProbabilities.getOrElse(c, 1.0)) }.toMap

      classSampleProbabilityFile match {
        case Some(f) => probs ++ Source.fromFile(f).getLines().map{
          s:String =>
            val p = s.split(":")
            (p(0), p(1).toDouble)
        }.toMap
        case None => probs
      }
    }

    override def getIters: Int = iters

    override def sampleProb(l : MulticlassLabel) : Double = {
      probabilityMap.getOrElse(l.getLabel(), 1.0)
    }

    override def modelPostProcess(m: UpdateableMulticlassLinearModel) : UpdateableMulticlassLinearModel = {
        m.thresholdParameters(finalThresholding)
        m.setArgString(args.toString)
        m.teardown()
        m
    }

    /**
     *  Choose an optimizer to use
     */
    val o = optimizerType match {
            case "elastic_net" => new ElasticNetOptimizer()
            case "adagrad" => new AdagradOptimizer()
            case "passive_aggressive" => new PassiveAggressiveOptimizer().setC(aggressiveness).isHinge(true)
            case "ftrl" => new FTRLOptimizer().setAlpha(ftrlAlpha).setBeta(ftrlBeta)
            case "mira" => new MIRAOptimizer()
        }

    val optimizer = o.setGaussianRegularizationWeight(gauss)
        .setLaplaceRegularizationWeight(laplace)
        .setExamplesPerEpoch(examplesPerEpoch)
        .setUseExponentialLearningRate(useExponentialLearningRate)
        .setExponentialLearningRateBase(exponentialLearningRateBase)
        .setInitialLearningRate(initialLearningRate)

    def buildMultiClassModel(buildSubModel : () => UpdateableLinearModel[BinaryLabel], categories : Array[String]) : UpdateableMulticlassLinearModel = {
        val param = categories.map{ i : String => 
            (i, buildSubModel().setTruncationPeriod(truncationPeriod)
                .setTruncationThreshold(truncationThresh)
                .setTruncationUpdate(truncationAlpha))
            }.toMap
        new UpdateableMulticlassLinearModel(new java.util.HashMap[String,UpdateableLinearModel[BinaryLabel]](param) )
    }

    if(modelType == "mira" && optimizerType != "mira"){
        throw new IllegalArgumentException("MIRA only uses a MIRAOptimizer");
    }

    def getModel: UpdateableMulticlassLinearModel = {
      val model = modelType match {
        case "perceptron" => buildMultiClassModel({() => new Hinge(optimizer).setThreshold(0.0)}, categories)
        case "linear_svm" => buildMultiClassModel({() => new Hinge(optimizer).setThreshold(1.0)}, categories)
        // TODO: re-make proper multiclass logistic regression instead of this one vs all thing.
        case "logistic_regression" => buildMultiClassModel({() => new LogisticRegression(optimizer)}, categories)
        // TODO: re-make multiclass mira.
        case "mira" => buildMultiClassModel({() => new MIRA()}, categories)
      }
      model.setModelType(modelType)
      model
    }

    val bins = args.getOrElse("bins", "100").toInt

    val trainer = if (args.boolean("large")) new LargeModelTrainer(this, bins) else new SmallModelTrainer(this)

    /** Size of minibatch for mini-batch training, defaults to 1 which is just SGD. **/
    val batchsz = args.getOrElse("mini_batch_size", "1").toInt
    override def miniBatchSize: Int = batchsz

    def train(instances: Pipe, instanceField: Symbol = 'instance, modelField: Symbol = 'model): Pipe = {
        trainer.train(instances, instanceField, modelField)
    }

    def reTrain(instances: Pipe, instanceField: Symbol, model: Pipe, modelField: Symbol): Pipe = {
        trainer.reTrain(instances, instanceField, model, modelField)
    }
}
