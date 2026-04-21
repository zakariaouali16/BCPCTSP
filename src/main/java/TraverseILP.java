import com.gurobi.gurobi.*;

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
     * Solves the Integer Linear Program using Gurobi.
     * @return true if an optimal solution was found, false otherwise.
     */
    public boolean solve() {
        int n = graph.n();

        try {
            // ---------------------------------------------------------
            // INITIALIZE ENVIRONMENT & MODEL
            // ---------------------------------------------------------
            GRBEnv env = new GRBEnv(true);
            env.set("WLSACCESSID", "356d318b-49e5-488c-adf2-e5e2d4bd1179");
            env.set("WLSSECRET", "2be2af93-98ae-4d46-b9f8-1a693ac175f6");
            env.set("LICENSEID", "2798939");
            env.set("LogFile", "ilp_solver.log");
            env.start();

            GRBModel model = new GRBModel(env);
            model.set(GRB.StringAttr.ModelName, "Budget_PCTSP");

            // ---------------------------------------------------------
            // 1. VARIABLES
            // ---------------------------------------------------------

            // x[i][j] = 1 if the route goes directly from city i to city j
            GRBVar[][] x = new GRBVar[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i != j) {
                        x[i][j] = model.addVar(0.0, 1.0, 0.0, GRB.BINARY, "x_" + i + "_" + j);
                    }
                }
            }

            // y[i] = 1 if city i is visited
            GRBVar[] y = new GRBVar[n];
            for (int i = 0; i < n; i++) {
                y[i] = model.addVar(0.0, 1.0, 0.0, GRB.BINARY, "y_" + i);
            }

            // u[i] for MTZ sub-tour elimination constraints
            GRBVar[] u = new GRBVar[n];
            for (int i = 0; i < n; i++) {
                u[i] = model.addVar(1.0, n - 1, 0.0, GRB.CONTINUOUS, "u_" + i);
            }

            // ---------------------------------------------------------
            // 2. CONSTRAINTS
            // ---------------------------------------------------------

            // A. Start and End node constraints
            // Force 0 incoming edges to the start node
            GRBLinExpr startIn = new GRBLinExpr();
            for (int i = 0; i < n; i++) {
                if (i != startNode) startIn.addTerm(1.0, x[i][startNode]);
            }
            model.addConstr(startIn, GRB.EQUAL, 0.0, "StartIn");

            // Force exactly 1 outgoing edge from the start node
            GRBLinExpr startOut = new GRBLinExpr();
            for (int j = 0; j < n; j++) {
                if (j != startNode) startOut.addTerm(1.0, x[startNode][j]);
            }
            model.addConstr(startOut, GRB.EQUAL, 1.0, "StartOut");

            // Force 0 outgoing edges from the end node
            GRBLinExpr endOut = new GRBLinExpr();
            for (int j = 0; j < n; j++) {
                if (j != endNode) endOut.addTerm(1.0, x[endNode][j]);
            }
            model.addConstr(endOut, GRB.EQUAL, 0.0, "EndOut");

            // Force exactly 1 incoming edge to the end node
            GRBLinExpr endIn = new GRBLinExpr();
            for (int i = 0; i < n; i++) {
                if (i != endNode) endIn.addTerm(1.0, x[i][endNode]);
            }
            model.addConstr(endIn, GRB.EQUAL, 1.0, "EndIn");


            // B. Flow Conservation
            // For every other node: if visited, it must have 1 incoming and 1 outgoing edge
            for (int k = 0; k < n; k++) {
                if (k != startNode && k != endNode) {
                    
                    // Incoming flow: sum(x[i][k]) == y[k]  => sum(x[i][k]) - y[k] == 0
                    GRBLinExpr flowIn = new GRBLinExpr();
                    flowIn.addTerm(-1.0, y[k]);
                    for (int i = 0; i < n; i++) {
                        if (i != k) flowIn.addTerm(1.0, x[i][k]);
                    }
                    model.addConstr(flowIn, GRB.EQUAL, 0.0, "FlowIn_" + k);

                    // Outgoing flow: sum(x[k][j]) == y[k]  => sum(x[k][j]) - y[k] == 0
                    GRBLinExpr flowOut = new GRBLinExpr();
                    flowOut.addTerm(-1.0, y[k]);
                    for (int j = 0; j < n; j++) {
                        if (k != j) flowOut.addTerm(1.0, x[k][j]);
                    }
                    model.addConstr(flowOut, GRB.EQUAL, 0.0, "FlowOut_" + k);
                }
            }

            // C. Distance / Budget Constraint
            GRBLinExpr budgetConstraint = new GRBLinExpr();
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i != j) {
                        budgetConstraint.addTerm(graph.shortestPath(i, j), x[i][j]);
                    }
                }
            }
            model.addConstr(budgetConstraint, GRB.LESS_EQUAL, budget, "Budget");

            // D. MTZ Sub-tour Elimination Constraint
            // Form: u_i - u_j + (n-1)*x_ij <= n - 2
            // Equivalent to: u_i - u_j + 1 <= (n-1)(1 - x_ij)
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i != j && i != startNode && j != startNode) {
                        GRBLinExpr mtz = new GRBLinExpr();
                        mtz.addTerm(1.0, u[i]);
                        mtz.addTerm(-1.0, u[j]);
                        mtz.addTerm(n - 1.0, x[i][j]);
                        model.addConstr(mtz, GRB.LESS_EQUAL, n - 2.0, "MTZ_" + i + "_" + j);
                    }
                }
            }

            // ---------------------------------------------------------
            // 3. OBJECTIVE (Maximize Total Prize)
            // ---------------------------------------------------------
            GRBLinExpr objective = new GRBLinExpr();
            for (int i = 0; i < n; i++) {
                objective.addTerm(graph.getPrize(i), y[i]);
            }
            model.setObjective(objective, GRB.MAXIMIZE);

            // ---------------------------------------------------------
            // 4. SOLVE & EXTRACT RESULTS
            // ---------------------------------------------------------
            model.set(GRB.DoubleParam.TimeLimit, 120.0); // 120 seconds limit
            model.optimize();

            int optimStatus = model.get(GRB.IntAttr.Status);

            if (optimStatus == GRB.Status.OPTIMAL || optimStatus == GRB.Status.TIME_LIMIT || optimStatus == GRB.Status.SUBOPTIMAL) {
                
                // If a solution exists, extract it
                if (model.get(GRB.IntAttr.SolCount) > 0) {
                    this.totalPrize = model.get(GRB.DoubleAttr.ObjVal);

                    // Extract the path by following the x variables that equal 1
                    int curr = startNode;
                    bestRoute.add(curr);

                    while (curr != endNode) {
                        boolean foundNext = false;
                        for (int j = 0; j < n; j++) {
                            // Because floating point values might not be exactly 1.0
                            if (curr != j && x[curr][j].get(GRB.DoubleAttr.X) > 0.5) {
                                this.totalDistance += graph.shortestPath(curr, j);
                                curr = j;
                                bestRoute.add(curr);
                                foundNext = true;
                                break;
                            }
                        }

                        // If we didn't find a place to go, break to avoid an infinite loop
                        if (!foundNext) {
                            System.out.println("Path broken or incomplete. Ending extraction.");
                            break;
                        }
                    }

                    // Memory management
                    model.dispose();
                    env.dispose();
                    return true;
                }
            }

            System.out.println("The solver could not find a solution in the given time.");
            // Memory management
            model.dispose();
            env.dispose();
            return false;

        } catch (GRBException e) {
            System.err.println("Gurobi Exception: " + e.getErrorCode() + ". " + e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------
    // GETTERS FOR MAIN.JAVA
    // ---------------------------------------------------------
    public List<Integer> getBestRoute() {
        return bestRoute;
    }

    public double getTotalDistance() {
        return totalDistance;
    }

    public double getTotalPrize() {
        return totalPrize;
    }
}