package com.example.mnist;

import android.os.AsyncTask;
import android.os.Environment;
import android.service.autofill.Dataset;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.util.Log;

import org.nd4j.linalg.dataset.DataSet;
import org.deeplearning4j.nn.conf.graph.LayerVertex;
import org.deeplearning4j.nn.gradient.Gradient;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.SDVariable;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.nd4j.linalg.api.ndarray.INDArrayStatistics;
import org.nd4j.linalg.api.ops.impl.layers.convolution.config.Conv2DConfig;
import org.datavec.api.io.labels.ParentPathLabelGenerator;
import org.datavec.api.split.FileSplit;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.datavec.image.loader.NativeImageLoader;
import org.datavec.image.recordreader.ImageRecordReader;
import org.deeplearning4j.datasets.datavec.RecordReaderDataSetIterator;
import org.deeplearning4j.nn.conf.graph.ElementWiseVertex;
import org.deeplearning4j.nn.conf.graph.MergeVertex;
import org.deeplearning4j.nn.conf.graph.SubsetVertex;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.ConvolutionLayer;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.nd4j.evaluation.classification.Evaluation;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.dataset.api.preprocessor.DataNormalization;
import org.nd4j.linalg.dataset.api.preprocessor.ImagePreProcessingScaler;
import org.deeplearning4j.nn.conf.*;
import org.deeplearning4j.nn.conf.layers.*;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.nd4j.linalg.learning.config.Nesterovs;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.shade.jackson.databind.ser.impl.IteratorSerializer;
import org.deeplearning4j.nn.workspace.LayerWorkspaceMgr;

import java.io.File;
import java.lang.Math;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;


public class MainActivity extends AppCompatActivity {
    private static final String basePath = Environment.getExternalStorageDirectory() + "/mnist";
    private static final String dataUrl = "http://github.com/myleott/mnist_png/raw/master/mnist_png.tar.gz";
    private long bottleNeckForwardFLOPS = 0;
    private long bottleNeckBackwardFLOPS = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button button = findViewById(R.id.button);

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ProgressBar bar = findViewById(R.id.progressBar);
                bar.setVisibility(View.VISIBLE);
                new Thread(new Task()).start();
            }
        });
    }

    private class Task implements Runnable {
        @Override
        public void run() {
            try {
                int[] nChannels = new int[]{16, 64, 256};
                int[] nBlocks = new int[]{4, 4, 4};
                int[] nStrides = new int[]{1, 2, 2};
                int dsCount = 2; // number of stride equals 2
                int channels = 3;
                int init_ds = 0;
                int in_ch = channels * (int) Math.pow(2, init_ds);
                int n = in_ch / 2;
                int outputNum = 10; // number of output classes
                final int numRows = 32;
                final int numColumns = 32;
                int rngSeed = 1234; // random number seed for reproducibility
                int numEpochs = 1; // number of epochs to perform
                Random randNumGen = new Random(rngSeed);
                int batchSize = 16; // batch size for each epoch
                int mult = 4;
                try {
                    if (!new File(basePath + "/cifar").exists()) {
                        Log.d("Data download", "Data downloaded from " + dataUrl);
                        String localFilePath = basePath + "/cifar.tgz";
                        if (DataUtilities.downloadFile(dataUrl, localFilePath)) {
                            DataUtilities.extractTarGz(localFilePath, basePath);
                        }
                    }
                    // vectorization of train data
                    File trainData = new File(basePath + "/cifar/train");
                    FileSplit trainSplit = new FileSplit(trainData, NativeImageLoader.ALLOWED_FORMATS, randNumGen);
                    ParentPathLabelGenerator labelMaker = new ParentPathLabelGenerator(); // parent path as the image label
                    ImageRecordReader trainRR = new ImageRecordReader(numRows, numColumns, channels, labelMaker);
                    trainRR.initialize(trainSplit);
                    DataSetIterator cifarTrain = new RecordReaderDataSetIterator(trainRR, batchSize, 1, outputNum);
                    // pixel values from 0-255 to 0-1 (min-max scaling)
                    DataNormalization scaler = new ImagePreProcessingScaler(0, 1);
                    scaler.fit(cifarTrain);
                    cifarTrain.setPreProcessor(scaler);

                    // vectorization of test data
                    File testData = new File(basePath + "/cifar/test");
                    FileSplit testSplit = new FileSplit(testData, NativeImageLoader.ALLOWED_FORMATS, randNumGen);
                    ImageRecordReader testRR = new ImageRecordReader(numRows, numColumns, channels, labelMaker);
                    testRR.initialize(testSplit);
                    DataSetIterator cifarTest = new RecordReaderDataSetIterator(testRR, batchSize, 1, outputNum);
                    cifarTest.setPreProcessor(scaler); // same normalization for better results

                    ComputationGraphConfiguration.GraphBuilder graph = new NeuralNetConfiguration.Builder()
                            .seed(rngSeed)
                            .activation(Activation.IDENTITY)
                            .updater(new Nesterovs(0.1, 0.9))
                            .weightInit(WeightInit.XAVIER)
                            .l1(1e-7)
                            .l2(5e-5)
                            .graphBuilder();
                    graph.addInputs("input").setInputTypes(InputType.convolutional(numRows, numColumns, channels)); //(3, 3, 32, 32)
                    String lastLayer = "input";
                    if (init_ds != 0) {
                        graph.addLayer("init_psi", new PsiLayer.Builder()
                                .BlockSize(init_ds)
                                .build(), "input");
                        lastLayer = "init_psi";
                    }

                    graph.addVertex("x0", new SubsetVertexN(0, n-1), lastLayer) //(3, 1, 32, 32)
                            .addVertex("tilde_x0", new SubsetVertexN(n, in_ch-1), lastLayer); //(3, 2, 32, 32)
                    ActivationLayer relu = new ActivationLayer.Builder()
                            .activation(Activation.RELU)
                            .build();
                    graph.addLayer("firstRelu", relu, "tilde_x0");
                    int in_ch_Block = in_ch;
                    String input1 = "x0"; //(3, 1, 32, 32)
                    String input2 = "firstRelu"; // (3, 2, 32, 32)
                    List<IRevBlock> blockList = new ArrayList<>();
                    for (int i = 0; i < 3; i++) { // for each stage
                        for (int j = 0; j < nBlocks[i]; j++) { // for each block in the stage
                            int stride = 1;
                            if (j == 0) {
                                stride = nStrides[i];
                            }
                            IRevBlock innerIRevBlock = new IRevBlock(graph, in_ch_Block, nChannels[i], stride,
                                    mult, input1, input2, String.valueOf(i) + String.valueOf(j));
                            String[] outputs = innerIRevBlock.getOutput();
                            input1 = outputs[0];
                            input2 = outputs[1];
                            blockList.add(innerIRevBlock);
                            in_ch_Block = 2 * nChannels[i];
                        }
                    }

                    ProbLayer probLayer = new ProbLayer(nChannels[nChannels.length - 1] * 2, outputNum, 8, 8,
                            WeightInit.XAVIER);

                    LossLayer lossLayer = new LossLayer.Builder(LossFunctions.LossFunction.MCXENT)
                            .activation(Activation.SOFTMAX)
                            .build();


                    graph.addVertex("merge", new MergeVertex(), input1, input2)
                            .addLayer("outputProb", probLayer,"merge")
                            .addLayer("output", lossLayer, "outputProb")
                            .setOutputs("output", "merge");;

                    ComputationGraphConfiguration conf = graph.build();
                    ComputationGraph model = new ComputationGraph(conf);
                    model.init();
//                    model.getConfiguration().setInferenceWorkspaceMode(WorkspaceMode.ENABLED);
//                    model.getConfiguration().setTrainingWorkspaceMode(WorkspaceMode.ENABLED);

                    Log.d("Output", "start training");

                    Log.d("train model", "Train model....");
                    for(int l=0; l<=numEpochs; l++) {
                        model.fit(cifarTrain);
                    }



//                    while (cifarTrain.hasNext()) {
//                        DataSet data = cifarTrain.next();
//                        INDArray features = data.getFeatures();
//                        INDArray label = data.getLabels();
//                        INDArray[] outputs = model.output(features);
//                        Gradient gradient = new DefaultGradient();
//                        INDArray merge = outputs[1];
//                        INDArray[] outputGradients = probLayer.gradient(merge, label);
//                        INDArray dwGradient = outputGradients[1];
//                        INDArray dbGradient = outputGradients[2];
//                        gradient.setGradientFor("outputProb_denseWeight", dwGradient);
//                        gradient.setGradientFor("outputProb_denseBias", dbGradient);
//                        INDArray[] lossGradient = Utils.splitHalf(outputGradients[0]);
//                        INDArray[] hiddens = Utils.splitHalf(features);
//                        HashMap<String, INDArray> gradientMap = computeGradient(model, hiddens[0], hiddens[1],
//                                nBlocks, blockList, lossGradient);
//                        for (Map.Entry<String, INDArray> entry : gradientMap.entrySet()) {
//                            gradient.setGradientFor(entry.getKey(), entry.getValue());
//                        }
//                        model.getUpdater().update(gradient, 0, 0, 3, LayerWorkspaceMgr.noWorkspaces());
//                    }
                    Log.d("Success!", "Success!!!!!!!!");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        }

    }

    // This function computes the total gradient of the graph without referring to the stored activation
    protected HashMap<String, INDArray> computeGradient(ComputationGraph model, INDArray y2, INDArray y1, int[] nBlocks,
                                                          List<IRevBlock> blockList, INDArray[] lossGradient) {

        HashMap<String, INDArray> gradsResult = new HashMap<>();
        // get dy1 and dy2
        INDArray dy1 = lossGradient[0];
        INDArray dy2 = lossGradient[1];

        int cnt = blockList.size() - 1;
        // from the last layer to the first layer
        for (int i = nBlocks.length - 1; i >= 0; i -= 1) { // for each stage
            for (int j = nBlocks[i] - 1; j >= 0; j -= 1) { // for each iRevBlock
                IRevBlock iRev = blockList.get(cnt);
                // get x1 and x2
                INDArray[] x = iRev.inverse(y1, y2);
                INDArray x1 = x[0];
                INDArray x2 = x[1];
                // update (and swap) y1 and y2
                y1 = x1;
                y2 = x2;
                // get gradients
                List<INDArray> gradients = iRev.gradient(x1, dy1, dy2);
                // update dy1 and dy2 (already swapped)
                String prefix = iRev.getPrefix();
                dy1 = gradients.get(0);
                dy2 = gradients.get(1);
                // save graidents
                gradsResult.put(prefix + "btnk_conv1Weight", gradients.get(2));
                gradsResult.put(prefix + "btnk_conv2Weight", gradients.get(3));
                gradsResult.put(prefix + "btnk_conv3Weight", gradients.get(4));
                cnt -= 1;
            }
        }

        return gradsResult;
    }

    private long getFlopCountConv(int channels, int filter_size, int num_filters,
                                  int outShapeH, int outShapeW) {
        return (2 * channels * filter_size * filter_size - 1) * num_filters * outShapeH * outShapeW;
    }

    private long getFlopCountFC(int inputSize, int outputSize) {
        return (2 * inputSize - 1) * outputSize;
    }

    private long getFlopCountConvBackward(int channels, int filter_size, int num_filters,
                                          int outShapeH, int outShapeW) {
        int out = outShapeH * outShapeW;
        int db = out;
        int dw = num_filters * ((2 * out - 1) * channels * filter_size * filter_size);
        int dx_cols = channels * filter_size * filter_size * (2 * num_filters - 1) * out;
        int dx = channels * filter_size * filter_size * out;
        return db + dw + dx_cols + dx;
    }

    private long getFlopCountFCBackward(int inputSize, int outputSize) {
        int db = outputSize;
        int dx = (2 * outputSize - 1) * inputSize;
        int dw = inputSize * outputSize;
        return db + dx + dw;
    }
}