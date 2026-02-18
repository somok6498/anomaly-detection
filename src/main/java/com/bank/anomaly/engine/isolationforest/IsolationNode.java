package com.bank.anomaly.engine.isolationforest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class IsolationNode {

    @JsonProperty("f")
    private int splitFeature;

    @JsonProperty("v")
    private double splitValue;

    @JsonProperty("l")
    private IsolationNode left;

    @JsonProperty("r")
    private IsolationNode right;

    @JsonProperty("s")
    private int size; // number of samples that reached this node (for leaf nodes)

    @JsonProperty("e")
    private boolean external; // true if this is a leaf node

    public IsolationNode() {}

    public static IsolationNode internalNode(int splitFeature, double splitValue,
                                              IsolationNode left, IsolationNode right) {
        IsolationNode node = new IsolationNode();
        node.splitFeature = splitFeature;
        node.splitValue = splitValue;
        node.left = left;
        node.right = right;
        node.external = false;
        return node;
    }

    public static IsolationNode externalNode(int size) {
        IsolationNode node = new IsolationNode();
        node.size = size;
        node.external = true;
        return node;
    }

    public double pathLength(double[] point, int currentDepth) {
        if (external) {
            return currentDepth + averagePathLength(size);
        }
        if (point[splitFeature] < splitValue) {
            return left.pathLength(point, currentDepth + 1);
        } else {
            return right.pathLength(point, currentDepth + 1);
        }
    }

    /**
     * Average path length of unsuccessful search in a BST (Equation 1 from the IF paper).
     * c(n) = 2H(n-1) - 2(n-1)/n where H(i) = ln(i) + Euler's constant (0.5772...)
     */
    public static double averagePathLength(int n) {
        if (n <= 1) return 0;
        if (n == 2) return 1;
        double harmonicNumber = Math.log(n - 1.0) + 0.5772156649;
        return 2.0 * harmonicNumber - (2.0 * (n - 1.0) / n);
    }

    // Getters for serialization
    public int getSplitFeature() { return splitFeature; }
    public double getSplitValue() { return splitValue; }
    public IsolationNode getLeft() { return left; }
    public IsolationNode getRight() { return right; }
    public int getSize() { return size; }
    public boolean isExternal() { return external; }
}
