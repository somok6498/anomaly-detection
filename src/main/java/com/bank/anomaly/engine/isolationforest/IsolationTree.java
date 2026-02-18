package com.bank.anomaly.engine.isolationforest;

import java.util.Random;

public class IsolationTree {

    private IsolationNode root;

    public IsolationTree() {}

    public IsolationTree(IsolationNode root) {
        this.root = root;
    }

    public static IsolationTree build(double[][] data, int maxDepth, Random random) {
        IsolationNode root = buildNode(data, 0, maxDepth, random);
        return new IsolationTree(root);
    }

    private static IsolationNode buildNode(double[][] data, int depth, int maxDepth, Random random) {
        int n = data.length;

        if (depth >= maxDepth || n <= 1) {
            return IsolationNode.externalNode(n);
        }

        int numFeatures = data[0].length;
        int featureIdx = random.nextInt(numFeatures);

        // Find min and max for this feature
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double[] row : data) {
            if (row[featureIdx] < min) min = row[featureIdx];
            if (row[featureIdx] > max) max = row[featureIdx];
        }

        // If all values are the same for this feature, it's a leaf
        if (min >= max) {
            return IsolationNode.externalNode(n);
        }

        double splitValue = min + random.nextDouble() * (max - min);

        // Partition data
        int leftCount = 0;
        for (double[] row : data) {
            if (row[featureIdx] < splitValue) leftCount++;
        }

        double[][] leftData = new double[leftCount][];
        double[][] rightData = new double[n - leftCount][];
        int li = 0, ri = 0;
        for (double[] row : data) {
            if (row[featureIdx] < splitValue) {
                leftData[li++] = row;
            } else {
                rightData[ri++] = row;
            }
        }

        IsolationNode left = buildNode(leftData, depth + 1, maxDepth, random);
        IsolationNode right = buildNode(rightData, depth + 1, maxDepth, random);

        return IsolationNode.internalNode(featureIdx, splitValue, left, right);
    }

    public double pathLength(double[] point) {
        return root.pathLength(point, 0);
    }

    public IsolationNode getRoot() { return root; }
    public void setRoot(IsolationNode root) { this.root = root; }
}
