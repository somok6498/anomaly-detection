package com.bank.anomaly.engine.isolationforest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class IsolationForest {

    private List<IsolationTree> trees;
    private int sampleSize;

    public IsolationForest() {
        this.trees = new ArrayList<>();
    }

    /**
     * Train the isolation forest on the given data.
     *
     * @param data       training samples, each row is a feature vector
     * @param numTrees   number of trees in the forest (typically 100)
     * @param sampleSize sub-sampling size per tree (typically 256)
     * @param seed       random seed for reproducibility
     */
    public void train(double[][] data, int numTrees, int sampleSize, long seed) {
        this.sampleSize = Math.min(sampleSize, data.length);
        int maxDepth = (int) Math.ceil(Math.log(this.sampleSize) / Math.log(2));
        this.trees = new ArrayList<>(numTrees);

        Random random = new Random(seed);

        for (int i = 0; i < numTrees; i++) {
            double[][] sample = subsample(data, this.sampleSize, random);
            trees.add(IsolationTree.build(sample, maxDepth, random));
        }
    }

    /**
     * Compute anomaly score for a single point.
     *
     * @return score between 0.0 (normal) and 1.0 (anomalous)
     *         Score > 0.5 indicates anomaly; score ≈ 0.5 is uncertain; score < 0.5 is normal
     */
    public double anomalyScore(double[] point) {
        if (trees.isEmpty()) return 0.0;

        double avgPathLength = 0.0;
        for (IsolationTree tree : trees) {
            avgPathLength += tree.pathLength(point);
        }
        avgPathLength /= trees.size();

        double c = IsolationNode.averagePathLength(sampleSize);
        if (c <= 0) return 0.0;

        // IF scoring formula: s(x, n) = 2^(-E(h(x)) / c(n))
        return Math.pow(2.0, -avgPathLength / c);
    }

    /**
     * Compute feature contributions — which features pushed this point toward anomaly.
     * Uses the approach: compute score with each feature replaced by its mean,
     * and see how much the score drops.
     */
    public double[] featureContributions(double[] point, double[] featureMeans) {
        double baseScore = anomalyScore(point);
        double[] contributions = new double[point.length];

        for (int i = 0; i < point.length; i++) {
            double[] modified = Arrays.copyOf(point, point.length);
            modified[i] = featureMeans[i];
            double modifiedScore = anomalyScore(modified);
            // How much does replacing this feature with its mean reduce the anomaly score?
            contributions[i] = Math.max(0, baseScore - modifiedScore);
        }

        return contributions;
    }

    private double[][] subsample(double[][] data, int size, Random random) {
        if (data.length <= size) {
            return Arrays.copyOf(data, data.length);
        }
        double[][] sample = new double[size][];
        // Fisher-Yates shuffle on indices
        int[] indices = new int[data.length];
        for (int i = 0; i < data.length; i++) indices[i] = i;
        for (int i = 0; i < size; i++) {
            int j = i + random.nextInt(data.length - i);
            int tmp = indices[i];
            indices[i] = indices[j];
            indices[j] = tmp;
            sample[i] = data[indices[i]];
        }
        return sample;
    }

    // Getters/setters for serialization
    public List<IsolationTree> getTrees() { return trees; }
    public void setTrees(List<IsolationTree> trees) { this.trees = trees; }
    public int getSampleSize() { return sampleSize; }
    public void setSampleSize(int sampleSize) { this.sampleSize = sampleSize; }
}
