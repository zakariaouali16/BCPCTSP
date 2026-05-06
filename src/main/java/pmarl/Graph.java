package pmarl;

/**
 * Source code example for "A Practical Introduction to Data
 * Structures and Algorithm Analysis, 3rd Edition (Java)"
 * by Clifford A. Shaffer
 * Copyright 2008-2011 by Clifford A. Shaffer
 *
 * Modified: added constructDirectShortestPath() for complete Euclidean graphs
 * to avoid O(n^3) Floyd-Warshall when all edges are present.
 */

import java.util.*;

/** Graph: Adjacency matrix */
class Graph {
    public double[][] matrix;               // The edge matrix
    private double[][] shortestMatrix;      // the updated edge matrix
    public int[][] shortestNext;            // path reconstruction matrix
    public int numEdge;
    public int[] Mark;
    public String[] nodeName;
    public int[] prize;

    public Graph() {}
    public Graph(int n) { Init(n); }

    public Graph(Graph G) {
        int n = G.n();
        this.Mark          = new int[n];
        this.matrix        = new double[n][n];
        this.shortestMatrix= new double[n][n];
        this.shortestNext  = new int[n][n];
        this.prize         = new int[n];
        for (int i = 0; i < n; i++) {
            this.Mark[i]  = G.Mark[i];
            this.prize[i] = G.prize[i];
            for (int j = 0; j < n; j++) {
                this.matrix[i][j]        = G.matrix[i][j];
                this.shortestMatrix[i][j]= G.shortestMatrix[i][j];
                this.shortestNext[i][j]  = G.shortestNext[i][j];
            }
        }
        this.numEdge = G.numEdge;
    }

    public void Init(int n) {
        Mark     = new int[n];
        matrix   = new double[n][n];
        numEdge  = 0;
        nodeName = new String[n];
        prize    = new int[n];
        Arrays.fill(nodeName, 0, n, "");
    }

    public int n() { return Mark.length; }
    public int e() { return numEdge; }

    public void setEdge(int i, int j, double wt) {
        assert wt != 0 : "Cannot set weight to 0";
        if (matrix[i][j] == 0) numEdge++;
        matrix[i][j] = wt;
    }

    public double weight(int i, int j)       { return matrix[i][j]; }
    public double shortestPath(int i, int j) { return shortestMatrix[i][j]; }

    public void   setMark(int v, int val)    { Mark[v] = val; }
    public int    getMark(int v)             { return Mark[v]; }
    public void   setName(int v, String val) { nodeName[v] = val; }
    public String getName(int v)             { return nodeName[v]; }
    public void   setPrize(int v, int val)   { prize[v] = val; }
    public int    getPrize(int v)            { return prize[v]; }
    public int    getLastNode()              { return n() - 1; }

    /**
     * O(n^2) initialization for COMPLETE EUCLIDEAN graphs (no missing edges).
     * Because Euclidean distances satisfy the triangle inequality, the direct
     * edge between any pair of nodes is already the shortest path — no
     * relaxation is needed.  Use this instead of constructShortestPath()
     * when missingProb == 0 to avoid the O(n^3) Floyd-Warshall cost.
     */
    public void constructDirectShortestPath() {
        shortestMatrix = new double[n()][n()];
        shortestNext   = new int[n()][n()];
        for (int i = 0; i < n(); i++) {
            for (int j = 0; j < n(); j++) {
                shortestMatrix[i][j] = matrix[i][j];
                shortestNext[i][j]   = j;
            }
        }
    }

    /**
     * Full Floyd-Warshall O(n^3) — only needed when missingProb > 0.
     */
    public void constructShortestPath() {
        shortestMatrix = new double[n()][n()];
        shortestNext   = new int[n()][n()];
        for (int i = 0; i < n(); i++) {
            for (int j = 0; j < n(); j++) {
                shortestMatrix[i][j] = matrix[i][j];
                shortestNext[i][j]   = j;
            }
        }
        for (int k = 0; k < n(); k++)
            for (int i = 0; i < n(); i++)
                for (int j = 0; j < n(); j++)
                    if (shortestMatrix[i][k] + shortestMatrix[k][j] < shortestMatrix[i][j]) {
                        shortestMatrix[i][j] = shortestMatrix[i][k] + shortestMatrix[k][j];
                        shortestNext[i][j]   = shortestNext[i][k];
                    }
    }

    public void printExtraPathIfNeeded(int start, int end, ArrayList<Integer> route) {
        if (shortestNext[start][end] != end) {
            while (start != end) {
                int next = shortestNext[start][end];
                System.out.printf(
                    "\tDetour from %-12s to %-12s (%.2f)\n",
                    getName(start), getName(next), weight(start, next));
                route.add(next);
                start = next;
            }
        } else {
            route.add(end);
        }
    }
}