package pmarl;

import java.util.*;

/**
 * Agent — represents one RL agent in the MARL Q-learning algorithm.
 *
 * PERFORMANCE FIX (vs original):
 *   The original code gave each agent a full deep-copy of the Graph (O(n^2)
 *   per agent per trial).  For n=1000 that dominates runtime.
 *   Here each agent shares the immutable parts of the graph (matrix,
 *   shortestMatrix, shortestNext, prize) and keeps ONLY its own marks[]
 *   array (O(n)).  Graph copies are eliminated entirely.
 */
class Agent {
    public int    total_prize;
    public int    statesCt;
    public int    curState;
    public double total_wt;
    public double budget;
    public boolean isDone;

    public ArrayList<Integer> indexPath;

    // Shared read-only graph reference (matrix, shortest paths, prizes)
    private final Graph sharedGraph;

    // Per-agent mutable state — only marks differ between agents
    private final int[] marks;

    public Agent(int statesCt, double budget, Graph sharedGraph) {
        this.statesCt    = statesCt;
        this.sharedGraph = sharedGraph;
        this.budget      = budget;
        this.marks       = new int[statesCt];
        // Copy the initial mark array from the shared graph
        for (int i = 0; i < statesCt; i++)
            this.marks[i] = sharedGraph.getMark(i);
        total_prize = 0;
        curState    = 0;
        total_wt    = 0;
        indexPath   = new ArrayList<>();
        isDone      = false;
    }

    public void setAgentMark(int v, int val) { marks[v] = val; }
    public int  getMark(int v)               { return marks[v]; }

    public double weight(int i, int v)       { return sharedGraph.matrix[i][v]; }
    public double shortestPath(int i, int v) { return sharedGraph.shortestPath(i, v); }
    public int    getLastNode()              { return sharedGraph.n() - 1; }
    public int    getPrize(int v)            { return sharedGraph.prize[v]; }

    /**
     * Returns the total prize collectible by moving from curState toward
     * `end` along the shortest path (collecting prizes of any unvisited
     * intermediate nodes if a detour is taken).
     */
    public int getTotalPrize(int end) {
        int start = curState;
        if (sharedGraph.shortestNext[start][end] != end) {
            int total = 0;
            while (start != end) {
                int next = sharedGraph.shortestNext[start][end];
                if (marks[next] == 0) {
                    total += sharedGraph.prize[next];
                    marks[next] = 1;
                }
                start = next;
            }
            return total;
        } else {
            return sharedGraph.prize[end];
        }
    }

    /** Reset all marks to their original values from the shared graph. */
    public void resetAgentMarks() {
        for (int i = 0; i < statesCt; i++)
            marks[i] = sharedGraph.getMark(i);
    }
}