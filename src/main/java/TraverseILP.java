import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;

import java.util.ArrayList;
import java.util.List;

public class TraverseILP {

    private Graph graph;
    private int startNode;
    private int endNode;
    private double budget;
    
    // Results
    private List<Integer> bestRoute;
    private double totalDistance;
    private double totalPrize;

    /**
     * Constructor to initialize the ILP Solver state
     */
    public TraverseILP(Graph graph, int startNode, int endNode, double budget) {
        this.graph = graph;
        this.startNode = startNode;
        this.endNode = endNode;
        this.budget = budget;
        this.bestRoute = new ArrayList<>();
        this.totalDistance = 0.0;
        this.totalPrize = 0.0;
    }

    /**
     * Solves the Integer Linear Program using Google OR-Tools.
     * @return true if an optimal solution was found, false otherwise.
     */
    public boolean solve() {
        // Ensure native libraries are loaded. 
        // Note: It's safer to also call this once at the very top of your main.java
        Loader.loadNativeLibraries();

        // Create the linear solver with the SCIP backend (good for Integer Programming)
        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            System.err.println("Could not create solver SCIP");
            return false;
        }

        int n = graph.n();
        double infinity = java.lang.Double.POSITIVE_INFINITY;

        // ---------------------------------------------------------
        // 1. VARIABLES
        // ---------------------------------------------------------
        
        // x[i][j] = 1 if the route goes directly from city i to city j
        MPVariable[][] x = new MPVariable[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    x[i][j] = solver.makeIntVar(0.0, 1.0, "x_" + i + "_" + j);
                }
            }
        }

        // y[i] = 1 if city i is visited
        MPVariable[] y = new MPVariable[n];
        for (int i = 0; i < n; i++) {
            y[i] = solver.makeIntVar(0.0, 1.0, "y_" + i);
        }

        // u[i] for MTZ sub-tour elimination constraints
        MPVariable[] u = new MPVariable[n];
        for (int i = 0; i < n; i++) {
            u[i] = solver.makeNumVar(1.0, n - 1, "u_" + i);
        }

        // ---------------------------------------------------------
        // 2. CONSTRAINTS
        // ---------------------------------------------------------

        // A. Start and End node constraints
        // Force the start node to be visited and have exactly 1 outgoing edge
        solver.makeConstraint(1, 1, "StartNodeVisited").setCoefficient(y[startNode], 1);
        MPConstraint startOut = solver.makeConstraint(1, 1, "StartOut");
        for (int j = 0; j < n; j++) {
            if (startNode != j) startOut.setCoefficient(x[startNode][j], 1);
        }

        // Force the end node to be visited and have exactly 1 incoming edge
        solver.makeConstraint(1, 1, "EndNodeVisited").setCoefficient(y[endNode], 1);
        MPConstraint endIn = solver.makeConstraint(1, 1, "EndIn");
        for (int i = 0; i < n; i++) {
            if (i != endNode) endIn.setCoefficient(x[i][endNode], 1);
        }

        // B. Flow Conservation
        // For every other node: if visited, it must have 1 incoming and 1 outgoing edge
        for (int k = 0; k < n; k++) {
            if (k != startNode && k != endNode) {
                MPConstraint flowIn = solver.makeConstraint(0, 0, "FlowIn_" + k);
                flowIn.setCoefficient(y[k], -1);
                for (int i = 0; i < n; i++) {
                    if (i != k) flowIn.setCoefficient(x[i][k], 1);
                }

                MPConstraint flowOut = solver.makeConstraint(0, 0, "FlowOut_" + k);
                flowOut.setCoefficient(y[k], -1);
                for (int j = 0; j < n; j++) {
                    if (k != j) flowOut.setCoefficient(x[k][j], 1);
                }
            }
        }

        // C. Distance / Budget Constraint
        MPConstraint budgetConstraint = solver.makeConstraint(0, budget, "Budget");
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    budgetConstraint.setCoefficient(x[i][j], graph.shortestPath(i, j));
                }
            }
        }

        // D. MTZ Sub-tour Elimination Constraint
        // u_i - u_j + 1 <= (n-1)(1 - x_ij)
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j && i != startNode && j != startNode) {
                    MPConstraint mtz = solver.makeConstraint(-infinity, n - 2, "MTZ_" + i + "_" + j);
                    mtz.setCoefficient(u[i], 1);
                    mtz.setCoefficient(u[j], -1);
                    mtz.setCoefficient(x[i][j], n - 1);
                }
            }
        }

        // ---------------------------------------------------------
        // 3. OBJECTIVE (Maximize Total Prize)
        // ---------------------------------------------------------
        MPObjective objective = solver.objective();
        for (int i = 0; i < n; i++) {
            objective.setCoefficient(y[i], graph.getPrize(i));
        }
        objective.setMaximization();

        // ---------------------------------------------------------
        // 4. SOLVE & EXTRACT RESULTS
        // ---------------------------------------------------------
        MPSolver.ResultStatus resultStatus = solver.solve();

        if (resultStatus == MPSolver.ResultStatus.OPTIMAL) {
            this.totalPrize = objective.value();
            
            // Extract the path by following the x variables that equal 1
            int curr = startNode;
            bestRoute.add(curr);
            
            while (curr != endNode) {
                for (int j = 0; j < n; j++) {
                    if (curr != j && x[curr][j].solutionValue() > 0.5) {
                        this.totalDistance += graph.shortestPath(curr, j);
                        curr = j;
                        bestRoute.add(curr);
                        break;
                    }
                }
            }
            return true;
        } else {
            System.out.println("The solver could not find an optimal solution.");
            return false;
        }
    }

    // ---------------------------------------------------------
    // GETTERS FOR MAIN.JAVA
    // ---------------------------------------------------------
    public List<Integer> getBestRoute() { return bestRoute; }
    public double getTotalDistance() { return totalDistance; }
    public double getTotalPrize() { return totalPrize; }
}