//General imports
import java.io.File
import java.util.HashMap
import java.util.ArrayList
import java.util.Random

import scala.io.StdIn.{readInt, readLine}
import BIDMat.FMat
import BIDMat.MatFunctions._
import BIDMat.SciFunctions._
import YADLL.FunctionGraph.Graph.OGraph
import YADLL.FunctionGraph.Operators.SpecOps.{KL_Gauss, KL_Piece}
import YADLL.FunctionGraph.Optimizers.{SGOpt, _}
import YADLL.FunctionGraph.Theta
import YADLL.Utils.{MathUtils, MiscUtils}
import YAVL.Data.Text.Lexicon.Lexicon
import YAVL.Utils.{Logger, ScalaDebugUtils}

import scala.runtime.{RichInt, RichInt$}
//Imports from BIDMat
import BIDMat.{CMat, CSMat, DMat, Dict, FMat, GIMat, GMat, GSMat, HMat, IDict, IMat, Mat, SBMat, SDMat, SMat}
import BIDMat.MatFunctions._
import BIDMat.SciFunctions._
//Imports from the library YADLL v0.7
import YADLL.FunctionGraph.BuildArch
import YADLL.Utils.ConfigFile

/**
  * Computes gradients of words w/ respect to Kl terms (either G-KL, P-KL, or both)
  * and ranks based on strength of gradients (i.e., L2-norms).
  *
  * Created by ago109 on 11/15/16.
  */
object WordSensitivityAnalyzer {
  Mat.checkMKL //<-- needed in order to check for BIDMat on cpu or gpu...

  //blockDerivFlow
  def main(args : Array[String]): Unit ={
    if(args.length != 1){
      System.err.println("usage: [/path/to/config.cfg]")
      return
    }
    //Extract arguments
    val configFile = new ConfigFile(args(0)) //grab configuration from local disk
    val seed = configFile.getArg("seed").toInt
    setseed(seed) //controls determinism of overall simulation
    val rng = new Random(seed)
    val dataFname = configFile.getArg("dataFname")
    val dictFname = configFile.getArg("dictFname")
    val graphFname = configFile.getArg("graphFname")
    val thetaFname = configFile.getArg("thetaFname")
    val labelFname = configFile.getArg("labelFname")
    val top_k = configFile.getArg("top_k").toInt
    var lookAtInputDeriv = configFile.getArg("lookAtInputDeriv").toBoolean
    if(lookAtInputDeriv){
      println(" > Building scores based on deriv of KL wrt input units")
    }else{
      println(" > Building scores based on L2 norms of KL wrt input embeddings")
    }
    var labelMap:HashMap[Int,String] = null
    if(configFile.isArgDefined("labelMapFname")){
      labelMap = new HashMap[Int,String]()
      val labelMapFname = configFile.getArg("labelMapFname")
      val lfd = MiscUtils.loadInFile(labelMapFname)
      var line = lfd.readLine()
      while(line != null){
        val tok = line.split("\\s+")
        labelMap.put(tok(1).toInt,tok(0))
        line = lfd.readLine()
      }
    }

    //Load in labels (1 per doc)
    val sorted_ptrs = new HashMap[Int,ArrayList[Int]]()
    val fd_lab = MiscUtils.loadInFile(labelFname)
    var line = fd_lab.readLine()
    var numRead = 0
    var idx = 0
    while(line != null){
      val lab = line.replaceAll(" ","").toInt
      var dat = sorted_ptrs.get(lab)
      if(dat != null){
        dat.add(idx)
      }else{
        dat = new ArrayList[Int]()
        dat.add(idx)
      }
      sorted_ptrs.put(lab,dat)
      numRead += 1
      print("\r > "+numRead + " labels read...")
      line = fd_lab.readLine()
      idx += 1
    }
    println()

    //Prop up sampler/lexicon
    val dict = new Lexicon(dictFname)
    val sampler = new DocSampler(dataFname,dict)
    sampler.loadDocsFromLibSVMToCache()

    //Load graph given config
    val archBuilder = new BuildArch()
    println(" > Loading Theta: "+thetaFname)
    val theta = archBuilder.loadTheta(thetaFname)
    println(" > Loading OGraph: "+graphFname)
    val graph = archBuilder.loadOGraph(graphFname)
    graph.theta = theta
    graph.hardClear() //<-- clear out any gunked up data from previous sessions

    //we need to know # of latent variables in model
    var n_lat = graph.getOp("z").dim
    if(graph.getOp("z-gaussian") != null){
      n_lat = graph.getOp("z-gaussian").dim
    }else if(graph.getOp("z-piece") != null){
      n_lat = graph.getOp("z-piece").dim
    }

    print(" > Enter a label-index (-1 to quit): ")
    var lab_select = readInt()
    println()
    while(lab_select != -1){ //infinite loop unless user inputs -1
      var docPtr = -1
      if(lab_select >= 0){
        val dat = sorted_ptrs.get(lab_select)
        if(dat.size() > 0){
          val s = MiscUtils.genRandInt(rng,0,dat.size())
          docPtr = dat.remove(s)
          sorted_ptrs.put(lab_select,dat)
        }else{
          println("  NO more data for "+lab_select)
        }
      }
      if(docPtr >= 0){
        if(labelMap != null){
          println(" >> LABEL => "+labelMap.get(lab_select))
        }
        val doc = sampler.getDocAt(docPtr)
        println(" DOC:: "+doc.toDocString(dict))
        //Build an index look-up table for current document
        val idxLookUp = new HashMap[Int,java.lang.Float]()
        var ii = 0
        while(ii < doc.bagOfIdx.length){
          idxLookUp.put(doc.bagOfIdx(ii),doc.bagOfVals(ii))
          ii += 1
        }
        val x = doc.getBOWVec()
        //val x_i = sparse(w_idx,0,1f,doc.dim,1) //create a useless y or target
        val x_0 = sparse(0,0,1f,doc.dim,1) //create a useless y or target
        if(lookAtInputDeriv)
          graph.getOp("x-in").muteDerivCalc(flag = false) //we want the derivative w/ respect to word inputs
        //Generate random sample for model as needed
        val eps_gauss: Mat = ones(n_lat,x.ncols) *@ normrnd(0f, 1f, n_lat, 1)
        val eps_piece: Mat = ones(n_lat,x.ncols) *@ rand(n_lat, 1)
        val numDocs = 1 //<-- all of the predictions are for a single document
        val KL_correction:Mat = ones(1,x.ncols) *@ x.ncols //correct for KL being copied equal to # of predictions

       //We call the inference routine eval() inside each potential KL-term block, since will need to tighten their bounds :)
        val KL_gauss_op = graph.getOp("KL-gauss")
        val KL_piece_op = graph.getOp("KL-piece")
        if(KL_gauss_op != null){
          println(" -> G-KL = "+graph.getStat("KL-gauss"))
          val trickBak = KL_gauss_op.asInstanceOf[KL_Gauss].maxTrickConstant
          KL_gauss_op.asInstanceOf[KL_Gauss].maxTrickConstant = 0f
          graph.clamp(("x-in", x))
          graph.clamp(("x-targ", x_0))
          graph.clamp(("eps-gauss", eps_gauss))
          graph.clamp(("eps-piece", eps_piece))
          graph.clamp(("KL-correction",KL_correction))
          graph.clamp(("N",numDocs)) //<-- note we don't want a mean loss this time...
          graph.eval() //do inference to gather statistics
          println(" Top("+top_k+") Most Sensitive to G-KL:")
          //Block gradient flow at the latent variable e
          graph.blockDerivFlow(graph.getOp("z"))
          if(KL_piece_op != null)
            graph.blockDerivFlow(KL_piece_op)
          val nabla = graph.calc_grad()
          var wordScores:Mat = null
          if(lookAtInputDeriv){
            wordScores = nabla.getParam("x-in") //get derivative of each term w/ respect to input words
          }else{
            val d_e = nabla.getParam("W0-enc") //deriv wrt embeddings (i.e., slices in 1st encoder matrix)
            //println(ScalaDebugUtils.printFullMat(d_e))
            wordScores = sqrt( sum(d_e *@ d_e,1) ).t //L2 norm of each embedding deriv
          }
          //Now build a ranked list based on gradients for each word in document
          val stat = sortdown2(wordScores) //highest/most positive values at top
          wordScores = stat._1.asInstanceOf[Mat]
          val sortedInd = stat._2.asInstanceOf[IMat]
          //With all scores sorted (and indices preserved), we may extract the ones in the document
          var  i = 0
          var k_found = 0
          while(i < wordScores.nrows && k_found < top_k){
            val score = FMat(wordScores)(i,0)
            val idx = sortedInd(i,0)
            if(idxLookUp.get(idx) != null){
              println(dict.getSymbol(idx) + " = "+score)
              k_found += 1
            }
            i += 1
          }
          graph.unblockDerivs()
          KL_gauss_op.asInstanceOf[KL_Gauss].maxTrickConstant = trickBak
        }

        graph.hardClear()
        if(KL_piece_op != null){
          println(" -> P-KL = "+graph.getStat("KL-piece"))
          val trickBak = KL_piece_op.asInstanceOf[KL_Piece].maxTrickConstant
          KL_piece_op.asInstanceOf[KL_Piece].maxTrickConstant = 0f
          graph.clamp(("x-in", x))
          graph.clamp(("x-targ", x_0))
          graph.clamp(("eps-gauss", eps_gauss))
          graph.clamp(("eps-piece", eps_piece))
          graph.clamp(("KL-correction",KL_correction))
          graph.clamp(("N",numDocs)) //<-- note we don't want a mean loss this time...
          graph.eval() //do inference to gather statistics
          println(" Top("+top_k+") Most Sensitive to P-KL:")
          //Block gradient flow at the latent variable z!
          graph.blockDerivFlow(graph.getOp("z"))
          if(KL_gauss_op != null)
            graph.blockDerivFlow(KL_gauss_op)
          val nabla = graph.calc_grad()
          //Now build a ranked list based on gradients for each word in document
          var wordScores:Mat = null
          if(lookAtInputDeriv){
            wordScores = nabla.getParam("x-in") //get derivative of each term w/ respect to input words
          }else{
            val d_e = nabla.getParam("W0-enc") //deriv wrt embeddings (i.e., slices in 1st encoder matrix)
            wordScores = sqrt( sum(d_e *@ d_e,1) ).t //L2 norm of each embedding deriv
          }
          //Now build a ranked list based on gradients for each word in document
          val stat = sortdown2(wordScores) //highest/most positive values at top
          wordScores = stat._1.asInstanceOf[Mat]
          val sortedInd = stat._2.asInstanceOf[IMat]
          //With all scores sorted (and indices preserved), we may extract the ones in the document
          var  i = 0
          var k_found = 0
          while(i < wordScores.nrows && k_found < top_k){
            val score = FMat(wordScores)(i,0)
            val idx = sortedInd(i,0)
            if(idxLookUp.get(idx) != null){
              println(dict.getSymbol(idx) + " = "+score)
              k_found += 1
            }
            i += 1
          }
          graph.unblockDerivs()
          KL_piece_op.asInstanceOf[KL_Piece].maxTrickConstant = trickBak
        }
      }
      graph.hardClear()
      print(" > Enter a label-index (-1 to quit): ")
      lab_select = readInt()
      println()
      if(lab_select == -2){
        if(lookAtInputDeriv)
          lookAtInputDeriv = false
        else
          lookAtInputDeriv = true
        println(" > Switching <lookAtInputDeriv> to "+lookAtInputDeriv)
      }
    }
  }


}