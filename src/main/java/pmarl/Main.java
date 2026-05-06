package pmarl;

import java.io.*;
import java.util.*;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;

/**
 * Main.java - 20-run experiment using usa_towns_with_rewards1000.csv.
 *
 * CSV format expected:
 *   Node_ID,X,Y,Reward
 *   1,33613.1588,86118.3061,75
 *   ...
 *
 * Default behavior:
 *   - Load the CSV once.
 *   - Choose 20 different start/end pairs from the CSV.
 *   - Do not reuse a town across the 20 runs.
 *   - Only choose pairs whose direct start-to-end distance fits inside budget.
 *   - For each run, rebuild the graph, run MARL Q-learning, then print reward
 *     and distance for the final path.
 *
 * Optional command-line arguments:
 *   java Main [csvFile] [runs] [budget] [seed] [ilpCandidateLimit]
 *
 * Example:
 *   java Main usa_towns_with_rewards1000.csv 20 5000 42 16
 */
public class Main {

    // File and run settings
    static String fileName = "usa_towns_with_rewards1000.csv";
    static int TOTAL_RUNS = 20;
    static double budget = 5_000.0;
    static long baseSeed = 42L;

    // Q-learning hyper-parameters
    static final int TRIALS = 200;
    static final int NUM_AGENTS = 5;
    static final double W = 1_000.0;
    static double alpha = 0.125;
    static double gamma = 0.35;
    static final double delta = 1.0;
    static final double beta = 2.0;
    static double q0 = 0.8;

    // OR-Tools ILP baseline settings.
    // ILP_CANDIDATE_LIMIT controls how many feasible intermediate towns the
    // OR-Tools model considers.
    //   Integer.MAX_VALUE (default) = ALL feasible towns -> globally optimal,
    //                                 but may be very slow for large feasible sets.
    //   A positive integer N         = top-N by prize/ratio (fast but approximate).
    //   0                            = skip ILP entirely.
    static int ILP_CANDIDATE_LIMIT = Integer.MAX_VALUE;

    // CBC can solve moderate candidate sets, but a 1000-town complete graph can
    // create a very large MIP. This cap is used only when candidateLimit is left
    // unlimited; pass an explicit ilpCandidateLimit to raise/lower it.
    static final int DEFAULT_ORTOOLS_CANDIDATE_CAP = 80;

    // Graph flags
    static final int UNVISITED = 0;
    static final int VISITED = 1;
    static final int LAST_VISIT = 2;

    // Raw CSV data loaded once
    static int rawN;
    static int[] rawId;
    static String[] rawName;
    static double[] rawX;
    static double[] rawY;
    static int[] rawReward;

    // Per-run state
    static String begin = "";
    static String end = "";
    static double remainingBudget;
    static LinkedList<CityNode> arrCities;
    static ArrayList<String> nameList;
    static Graph sGraph;
    static double[][] Q;
    static double[][] R;
    static int statesCt;
    static int total_prize;
    static double total_wt;
    static ArrayList<Integer> route;
    static ArrayList<Integer> routeMax;
    static int routeMaxIter;
    static int prizeMax;
    static Random stepRandom;

    static class AlgoResult {
        String name;
        int reward;
        double distance;
        double remaining;
        int visited;
        long timeMs;
        ArrayList<Integer> route;
        String note;

        AlgoResult(String name, int reward, double distance, double remaining,
                   int visited, long timeMs, ArrayList<Integer> route, String note) {
            this.name = name;
            this.reward = reward;
            this.distance = distance;
            this.remaining = remaining;
            this.visited = visited;
            this.timeMs = timeMs;
            this.route = route;
            this.note = note;
        }
    }

    public static void main(String[] args) throws IOException {
        readArgs(args);

        loadCSVData(fileName);
        System.out.printf("Loaded %,d towns from %s%n", rawN, fileName);
        System.out.printf(
                "Runs: %d | Budget: %.2f | Seed: %d | ILP candidate limit: %s%n%n",
                TOTAL_RUNS, budget, baseSeed,
                (ILP_CANDIDATE_LIMIT == Integer.MAX_VALUE ? "unlimited (all feasible)" : String.valueOf(ILP_CANDIDATE_LIMIT)));

        int[][] runPairs = chooseRunPairs(TOTAL_RUNS, budget, baseSeed);
        AlgoResult[][] results = new AlgoResult[TOTAL_RUNS][4];
        String[] allStarts = new String[TOTAL_RUNS];
        String[] allEnds = new String[TOTAL_RUNS];

        for (int run = 1; run <= TOTAL_RUNS; run++) {
            int si = runPairs[run - 1][0];
            int ei = runPairs[run - 1][1];

            begin = rawName[si];
            end = rawName[ei];
            allStarts[run - 1] = begin;
            allEnds[run - 1] = end;

            System.out.printf(
                    "============================================================%n" +
                    " RUN %2d / %d | Start: %-10s End: %-10s Direct: %.2f%n" +
                    "============================================================%n",
                    run, TOTAL_RUNS, begin, end, rawDistance(si, ei));

            results[run - 1][0] = runGreedyPrize();
            printRunResult(results[run - 1][0]);

            results[run - 1][1] = runGreedyRatio();
            printRunResult(results[run - 1][1]);

            stepRandom = new Random(baseSeed + 10_000L * run);
            results[run - 1][2] = runPMARL();
            printRunResult(results[run - 1][2]);

            results[run - 1][3] = runIlpExactSubset();
            printRunResult(results[run - 1][3]);

            System.out.println();
        }

        printComparisonSummary(allStarts, allEnds, results);
    }

    static void readArgs(String[] args) {
        if (args.length >= 1 && !args[0].trim().isEmpty()) {
            fileName = args[0].trim();
        }
        if (args.length >= 2) {
            TOTAL_RUNS = Integer.parseInt(args[1]);
        }
        if (args.length >= 3) {
            budget = Double.parseDouble(args[2]);
        }
        if (args.length >= 4) {
            baseSeed = Long.parseLong(args[3]);
        }
        if (args.length >= 5) {
            ILP_CANDIDATE_LIMIT = Integer.parseInt(args[4]);
        }
        if (TOTAL_RUNS <= 0) {
            throw new IllegalArgumentException("runs must be positive");
        }
        if (budget <= 0) {
            throw new IllegalArgumentException("budget must be positive");
        }
        if (ILP_CANDIDATE_LIMIT < 0) {
            throw new IllegalArgumentException(
                "ilpCandidateLimit must be >= 0  (0 = skip ILP, omit arg or pass " +
                Integer.MAX_VALUE + " = all feasible towns)");
        }
    }

    static void loadCSVData(String file) throws IOException {
        File f = locateFile(file);
        if (f == null) {
            throw new FileNotFoundException("CSV file not found: " + file);
        }

        ArrayList<Integer> ids = new ArrayList<>();
        ArrayList<Double> xs = new ArrayList<>();
        ArrayList<Double> ys = new ArrayList<>();
        ArrayList<Integer> rewards = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = line.split(",");
                if (lineNo == 1 && parts[0].trim().equalsIgnoreCase("Node_ID")) {
                    continue;
                }
                if (parts.length < 4) {
                    throw new IOException("Bad CSV line " + lineNo + ": " + line);
                }

                ids.add(Integer.parseInt(parts[0].trim()));
                xs.add(Double.parseDouble(parts[1].trim()));
                ys.add(Double.parseDouble(parts[2].trim()));
                rewards.add(Integer.parseInt(parts[3].trim()));
            }
        }

        rawN = ids.size();
        if (rawN == 0) {
            throw new IOException("CSV contains no town rows: " + file);
        }
        if (rawN < 2 * TOTAL_RUNS) {
            throw new IllegalArgumentException(
                    "Need at least " + (2 * TOTAL_RUNS) +
                    " towns to run " + TOTAL_RUNS +
                    " runs without reusing start/end towns.");
        }

        rawId = new int[rawN];
        rawName = new String[rawN];
        rawX = new double[rawN];
        rawY = new double[rawN];
        rawReward = new int[rawN];

        for (int i = 0; i < rawN; i++) {
            rawId[i] = ids.get(i);
            rawName[i] = "Town_" + rawId[i];
            rawX[i] = xs.get(i);
            rawY[i] = ys.get(i);
            rawReward[i] = rewards.get(i);
        }
    }

    static File locateFile(String file) {
        File[] candidates = new File[] {
                new File(file),
                new File("src/" + file),
                new File("src/main/java/" + file),
                new File("src/main/resources/" + file),
                new File("/mnt/data/" + file)
        };
        for (File candidate : candidates) {
            if (candidate.exists() && candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    static int[][] chooseRunPairs(int runs, double maxDirectDistance, long seed) {
        int[][] pairs = new int[runs][2];
        boolean[] used = new boolean[rawN];
        Random rand = new Random(seed);

        for (int r = 0; r < runs; r++) {
            boolean found = false;
            for (int attempt = 0; attempt < 500_000 && !found; attempt++) {
                int si = rand.nextInt(rawN);
                int ei = rand.nextInt(rawN);

                if (si == ei || used[si] || used[ei]) {
                    continue;
                }
                if (rawDistance(si, ei) <= maxDirectDistance) {
                    pairs[r][0] = si;
                    pairs[r][1] = ei;
                    used[si] = true;
                    used[ei] = true;
                    found = true;
                }
            }

            if (!found) {
                throw new IllegalStateException(
                        "Could not find enough unique start/end pairs within budget " +
                        maxDirectDistance + ". Increase the budget or reduce the run count.");
            }
        }
        return pairs;
    }

    static double rawDistance(int i, int j) {
        double dx = rawX[j] - rawX[i];
        double dy = rawY[j] - rawY[i];
        return Math.sqrt(dx * dx + dy * dy);
    }

    static void buildCityList() {
        arrCities = new LinkedList<>();
        nameList = new ArrayList<>();

        for (int i = 0; i < rawN; i++) {
            CityNode cn = new CityNode(rawName[i], rawX[i], rawY[i], rawReward[i]);
            cn.originalIndex = i;
            arrCities.add(cn);
            nameList.add(rawName[i].toLowerCase());
        }

        String startCity = begin.toLowerCase();
        String endCity = end.toLowerCase();

        if (!nameList.contains(startCity) || !nameList.contains(endCity)) {
            throw new IllegalArgumentException("Cannot find town: " + begin + " or " + end);
        }

        int si = nameList.indexOf(startCity);
        CityNode start = new CityNode(arrCities.get(si));
        start.originalIndex = arrCities.get(si).originalIndex;
        arrCities.remove(si);
        nameList.remove(si);

        int ei = nameList.indexOf(endCity);
        CityNode finish = new CityNode(arrCities.get(ei));
        finish.originalIndex = arrCities.get(ei).originalIndex;
        arrCities.remove(ei);
        nameList.remove(ei);

        arrCities.add(0, start);
        arrCities.add(finish);
    }

    static void initGraph() {
        sGraph = new Graph();
        int size = arrCities.size();
        sGraph.Init(size);

        for (int i = 0; i < size; i++) {
            sGraph.setMark(i, UNVISITED);
            sGraph.setName(i, arrCities.get(i).name);
            sGraph.setPrize(i, arrCities.get(i).pop);

            for (int j = 0; j <= i; j++) {
                if (i == j) {
                    sGraph.setEdge(i, j, 0);
                } else {
                    double d = CityNode.getDistance(arrCities.get(i), arrCities.get(j));
                    sGraph.setEdge(i, j, d);
                    sGraph.setEdge(j, i, d);
                }
            }
        }

        sGraph.setMark(sGraph.getLastNode(), LAST_VISIT);
        sGraph.constructDirectShortestPath();
        statesCt = sGraph.n();
    }

    static void initStatics() {
        statesCt = sGraph.n();
        Q = new double[statesCt][statesCt];
        R = new double[statesCt][statesCt];

        for (int i = 0; i < statesCt; i++) {
            for (int j = 0; j < statesCt; j++) {
                double w = sGraph.weight(i, j);
                R[i][j] = 0;
                Q[i][j] = (w > 0) ? (sGraph.getPrize(i) + sGraph.getPrize(j)) / w : 0.0;
            }
        }
    }

    static void learnQ() {
        for (int i = 0; i < TRIALS; i++) {
            Agent[] aList = new Agent[NUM_AGENTS];
            for (int j = 0; j < NUM_AGENTS; j++) {
                aList[j] = new Agent(statesCt, budget, sGraph);
                aList[j].indexPath.add(0);
            }

            while (!allQuotaMet(aList)) {
                for (int j = 0; j < NUM_AGENTS; j++) {
                    Agent aj = aList[j];
                    if (!aj.isDone) {
                        int nextState = getNextState(aj, aj.curState,
                                1.0 - q0 * (TRIALS - i) / (double) TRIALS);

                        if (nextState == aj.getLastNode()) {
                            aj.isDone = true;
                        }

                        double maxQ = maxQ(aj, nextState);
                        Q[aj.curState][nextState] =
                                (1 - alpha) * Q[aj.curState][nextState] + alpha * gamma * maxQ;

                        aj.indexPath.add(nextState);
                        aj.total_wt += aj.shortestPath(aj.curState, nextState);
                        aj.total_prize += aj.getTotalPrize(nextState);
                        aj.setAgentMark(nextState, VISITED);
                        aj.curState = nextState;
                    }
                }
            }

            int mostFit = findHighestPrize(aList);
            Agent jStar = aList[mostFit];
            ArrayList<Integer> path = jStar.indexPath;
            jStar.resetAgentMarks();

            for (int v = 0; v < path.size() - 1; v++) {
                int a = path.get(v);
                int b = path.get(v + 1);
                double q = Q[a][b];
                double maxQ = maxQ(jStar, b);
                R[a][b] += W / Math.max(1, jStar.total_prize);
                Q[a][b] = (1 - alpha) * q + alpha * (R[a][b] + gamma * maxQ);
            }

            if (jStar.total_prize > prizeMax) {
                prizeMax = jStar.total_prize;
                routeMax = new ArrayList<>(path);
                routeMaxIter = i;

                for (int v = 0; v < path.size() - 1; v++) {
                    int a = path.get(v);
                    int b = path.get(v + 1);
                    double q = Q[a][b];
                    double maxQ = maxQ(jStar, b);
                    R[a][b] += i * W / Math.max(1, jStar.total_prize);
                    Q[a][b] = (1 - alpha) * q + alpha * (R[a][b] + gamma * maxQ);
                }
            }
        }
    }

    static boolean allQuotaMet(Agent[] aList) {
        for (Agent a : aList) {
            if (!a.isDone) {
                return false;
            }
        }
        return true;
    }

    static int findHighestPrize(Agent[] aList) {
        int idx = 0;
        double best = Double.NEGATIVE_INFINITY;
        for (int j = 0; j < aList.length; j++) {
            if (aList[j].total_prize > best) {
                best = aList[j].total_prize;
                idx = j;
            }
        }
        return idx;
    }

    static int getNextState(Agent aj, int s, double exploitProb) {
        ArrayList<Integer> feasible = new ArrayList<>();
        for (int i = 1; i < aj.getLastNode(); i++) {
            if (aj.getMark(i) == UNVISITED &&
                    aj.shortestPath(s, i) + aj.shortestPath(i, aj.getLastNode()) < aj.budget - aj.total_wt) {
                feasible.add(i);
            }
        }

        if (feasible.isEmpty()) {
            return aj.getLastNode();
        }

        if (stepRandom.nextDouble() > exploitProb) {
            double[] prob = new double[feasible.size()];
            double total = 0;

            for (int i = 0; i < feasible.size(); i++) {
                int u = feasible.get(i);
                prob[i] = Math.pow(Q[s][u], delta) * aj.getPrize(u) /
                        Math.pow(aj.weight(s, u), beta);
                total += prob[i];
            }

            if (total <= 0 || Double.isNaN(total) || Double.isInfinite(total)) {
                return feasible.get(stepRandom.nextInt(feasible.size()));
            }

            for (int i = 0; i < feasible.size(); i++) {
                prob[i] /= total;
            }

            double target = stepRandom.nextDouble();
            int idx = 0;
            while (idx < feasible.size() - 1 && target > prob[idx]) {
                target -= prob[idx];
                idx++;
            }
            return feasible.get(idx);
        }

        int maxIdx = 0;
        double curMax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < feasible.size(); i++) {
            int u = feasible.get(i);
            double val = Math.pow(Q[s][u], delta) * aj.getPrize(u) /
                    Math.pow(aj.weight(s, u), beta);
            if (val > curMax) {
                maxIdx = i;
                curMax = val;
            }
        }
        return feasible.get(maxIdx);
    }

    static double maxQ(Agent aj, int nextState) {
        double maxValue = 0;
        for (int i = 0; i < statesCt; i++) {
            if (aj.shortestPath(nextState, i) != 0 && aj.getMark(i) == UNVISITED) {
                double v = Q[nextState][i];
                if (v > maxValue) {
                    maxValue = v;
                }
            }
        }
        return maxValue;
    }

    static void traverseQ() {
        total_wt = 0;
        total_prize = 0;
        remainingBudget = budget;
        route = new ArrayList<>();

        for (int i = 0; i < statesCt; i++) {
            sGraph.setMark(i, UNVISITED);
        }
        sGraph.setMark(sGraph.getLastNode(), LAST_VISIT);

        int curState = 0;
        sGraph.setMark(curState, VISITED);
        route.add(curState);

        while (curState != sGraph.getLastNode()) {
            int nextState = getHighestQ(curState);

            if (nextState == -1) {
                nextState = sGraph.getLastNode();
                if (total_wt + sGraph.shortestPath(curState, nextState) > budget) {
                    break;
                }
            }

            sGraph.setMark(nextState, VISITED);
            sGraph.printExtraPathIfNeeded(curState, nextState, route);
            total_wt += sGraph.shortestPath(curState, nextState);
            curState = nextState;
        }

        total_prize = 0;
        for (int town : route) {
            if (sGraph.getMark(town) != 42) {
                sGraph.setMark(town, 42);
                total_prize += sGraph.getPrize(town);
            }
        }
        remainingBudget = budget - total_wt;
    }

    static int getHighestQ(int v) {
        double runningHigh = Double.NEGATIVE_INFINITY;
        int index = -1;

        for (int i = 1; i < sGraph.getLastNode(); i++) {
            if (Q[v][i] > runningHigh &&
                    sGraph.getMark(i) == UNVISITED &&
                    sGraph.shortestPath(v, i) + sGraph.shortestPath(i, sGraph.getLastNode()) < budget - total_wt) {
                runningHigh = Q[v][i];
                index = i;
            }
        }
        return index;
    }

    static AlgoResult runGreedyPrize() {
        buildCityList();
        initGraph();

        long t0 = System.currentTimeMillis();
        traverseP();
        long ms = System.currentTimeMillis() - t0;

        return captureResult("Greedy prize", ms, "highest reward first");
    }

    static AlgoResult runGreedyRatio() {
        buildCityList();
        initGraph();

        long t0 = System.currentTimeMillis();
        traverseR();
        long ms = System.currentTimeMillis() - t0;

        return captureResult("Greedy ratio", ms, "highest reward/distance first");
    }

    static AlgoResult runPMARL() {
        buildCityList();
        initGraph();
        initStatics();

        prizeMax = Integer.MIN_VALUE;
        routeMax = null;
        routeMaxIter = 0;

        long t0 = System.currentTimeMillis();
        learnQ();
        traverseQ();
        long ms = System.currentTimeMillis() - t0;

        return captureResult("PMARL", ms, "best training episode " + routeMaxIter);
    }

    static AlgoResult runIlpExactSubset() {
        buildCityList();
        initGraph();

        long t0 = System.currentTimeMillis();
        AlgoResult result = solveExactSubsetBaseline(ILP_CANDIDATE_LIMIT);
        result.timeMs = System.currentTimeMillis() - t0;
        return result;
    }

    static AlgoResult captureResult(String name, long timeMs, String note) {
        return new AlgoResult(
                name,
                total_prize,
                total_wt,
                remainingBudget,
                countUnique(route),
                timeMs,
                new ArrayList<>(route),
                note);
    }

    static void resetTraversal() {
        total_wt = 0;
        total_prize = 0;
        remainingBudget = budget;
        route = new ArrayList<>();
        for (int i = 0; i < statesCt; i++) {
            sGraph.setMark(i, UNVISITED);
        }
        sGraph.setMark(sGraph.getLastNode(), LAST_VISIT);
    }

    static ArrayList<Integer> getFeasibleSet(int s) {
        ArrayList<Integer> feasible = new ArrayList<>();
        for (int i = 1; i < sGraph.getLastNode(); i++) {
            if (sGraph.getMark(i) == UNVISITED &&
                    sGraph.shortestPath(s, i) + sGraph.shortestPath(i, sGraph.getLastNode()) < budget - total_wt) {
                feasible.add(i);
            }
        }
        return feasible;
    }

    // Greedy #1: always try the remaining town with the largest reward.
    static void traverseP() {
        resetTraversal();

        ArrayList<Integer> sortedNodes = new ArrayList<>();
        for (int i = 1; i < sGraph.getLastNode(); i++) {
            sortedNodes.add(i);
        }
        sortedNodes.sort(Comparator.comparingInt((Integer o) -> sGraph.getPrize(o)).reversed());

        int cur = 0;
        route.add(cur);
        for (int node : sortedNodes) {
            if (getFeasibleSet(cur).contains(node)) {
                sGraph.printExtraPathIfNeeded(cur, node, route);
                total_wt += sGraph.shortestPath(cur, node);
                cur = node;
                sGraph.setMark(node, VISITED);
            }
        }

        addFinishIfFeasible(cur);
        finishTraversalPrizeCount();
    }

    // Greedy #2: from the current town, choose the feasible town with the best
    // reward/distance ratio.
    static void traverseR() {
        resetTraversal();

        int cur = 0;
        route.add(cur);

        while (true) {
            ArrayList<Integer> feasible = getFeasibleSet(cur);
            if (feasible.isEmpty()) {
                break;
            }

            int best = -1;
            double bestRatio = Double.NEGATIVE_INFINITY;
            for (int node : feasible) {
                double d = sGraph.shortestPath(cur, node);
                double ratio = (d <= 0.0) ? Double.POSITIVE_INFINITY : sGraph.getPrize(node) / d;
                if (ratio > bestRatio) {
                    bestRatio = ratio;
                    best = node;
                }
            }

            sGraph.printExtraPathIfNeeded(cur, best, route);
            total_wt += sGraph.shortestPath(cur, best);
            cur = best;
            sGraph.setMark(best, VISITED);
        }

        addFinishIfFeasible(cur);
        finishTraversalPrizeCount();
    }

    static void addFinishIfFeasible(int cur) {
        int finish = sGraph.getLastNode();
        if (cur != finish && total_wt + sGraph.shortestPath(cur, finish) <= budget) {
            sGraph.printExtraPathIfNeeded(cur, finish, route);
            total_wt += sGraph.shortestPath(cur, finish);
        }
        remainingBudget = budget - total_wt;
    }

    static void finishTraversalPrizeCount() {
        total_prize = 0;
        boolean[] counted = new boolean[statesCt];
        for (int town : route) {
            if (!counted[town]) {
                counted[town] = true;
                total_prize += sGraph.getPrize(town);
            }
        }
        remainingBudget = budget - total_wt;
    }

    static AlgoResult solveExactSubsetBaseline(int candidateLimit) {
        ArrayList<Integer> candidates = chooseIlpCandidates(candidateLimit);
        int feasibleCount = countFeasibleIntermediateNodes();
        int startNode = 0;
        int finishNode = sGraph.getLastNode();

        total_wt = sGraph.shortestPath(startNode, finishNode);
        route = new ArrayList<>();
        route.add(startNode);
        if (startNode != finishNode) {
            route.add(finishNode);
        }
        total_prize = prizeForRoute(route);
        remainingBudget = budget - total_wt;

        if (candidateLimit == Integer.MAX_VALUE && candidates.size() > DEFAULT_ORTOOLS_CANDIDATE_CAP) {
            System.out.printf(
                "  [OR-Tools ILP] %d feasible towns is a large MIP.\n" +
                "                 Trimming to top-%d by prize/ratio. Pass an explicit\n" +
                "                 ilpCandidateLimit to control this cap.\n",
                candidates.size(), DEFAULT_ORTOOLS_CANDIDATE_CAP);
            candidates = trimCandidates(candidates, DEFAULT_ORTOOLS_CANDIDATE_CAP);
        }

        int k = candidates.size();
        if (k == 0) {
            String note = (candidateLimit == 0)
                    ? "candidate limit is 0"
                    : "no feasible intermediate candidates";
            return captureResult("OR-Tools ILP", 0, note);
        }

        Loader.loadNativeLibraries();
        MPSolver solver = MPSolver.createSolver("CBC_MIXED_INTEGER_PROGRAMMING");
        if (solver == null) {
            throw new IllegalStateException("Could not create OR-Tools CBC MIP solver.");
        }
        solver.setTimeLimit(120_000L);

        int m = k + 2;
        int start = 0;
        int finish = m - 1;
        int[] localToGraph = new int[m];
        localToGraph[start] = startNode;
        for (int i = 0; i < k; i++) {
            localToGraph[i + 1] = candidates.get(i);
        }
        localToGraph[finish] = finishNode;

        MPVariable[][] x = new MPVariable[m][m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) {
                if (i != j && i != finish && j != start) {
                    x[i][j] = solver.makeBoolVar("x_" + i + "_" + j);
                }
            }
        }

        MPVariable[] y = new MPVariable[m];
        y[start] = solver.makeIntVar(1.0, 1.0, "y_start");
        y[finish] = solver.makeIntVar(1.0, 1.0, "y_finish");
        for (int i = 1; i < finish; i++) {
            y[i] = solver.makeBoolVar("y_" + i);
        }

        // MTZ order variables remove subtours and force a single start-to-finish path.
        MPVariable[] order = new MPVariable[m];
        order[start] = solver.makeNumVar(0.0, 0.0, "u_start");
        for (int i = 1; i < m; i++) {
            order[i] = solver.makeNumVar(1.0, m - 1, "u_" + i);
        }

        // Start has exactly one outgoing arc; finish has exactly one incoming arc.
        MPConstraint startOut = solver.makeConstraint(1.0, 1.0, "start_out");
        for (int j = 1; j < m; j++) {
            if (x[start][j] != null) startOut.setCoefficient(x[start][j], 1.0);
        }

        MPConstraint finishIn = solver.makeConstraint(1.0, 1.0, "finish_in");
        for (int i = 0; i < finish; i++) {
            if (x[i][finish] != null) finishIn.setCoefficient(x[i][finish], 1.0);
        }

        // Each selected intermediate town has one incoming and one outgoing arc.
        for (int v = 1; v < finish; v++) {
            MPConstraint in = solver.makeConstraint(0.0, 0.0, "flow_in_" + v);
            for (int i = 0; i < m; i++) {
                if (x[i][v] != null) in.setCoefficient(x[i][v], 1.0);
            }
            in.setCoefficient(y[v], -1.0);

            MPConstraint out = solver.makeConstraint(0.0, 0.0, "flow_out_" + v);
            for (int j = 0; j < m; j++) {
                if (x[v][j] != null) out.setCoefficient(x[v][j], 1.0);
            }
            out.setCoefficient(y[v], -1.0);
        }

        MPConstraint budgetConstraint = solver.makeConstraint(Double.NEGATIVE_INFINITY, budget, "budget");
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) {
                if (x[i][j] != null) {
                    double d = sGraph.shortestPath(localToGraph[i], localToGraph[j]);
                    budgetConstraint.setCoefficient(x[i][j], d);
                }
            }
        }

        int bigM = m;
        for (int i = 0; i < m; i++) {
            for (int j = 1; j < m; j++) {
                if (i == j || x[i][j] == null) continue;
                // order[i] + 1 <= order[j] + M * (1 - x[i][j])
                MPConstraint mtz = solver.makeConstraint(Double.NEGATIVE_INFINITY, bigM - 1, "mtz_" + i + "_" + j);
                mtz.setCoefficient(order[i], 1.0);
                mtz.setCoefficient(order[j], -1.0);
                mtz.setCoefficient(x[i][j], bigM);
            }
        }

        MPObjective objective = solver.objective();
        objective.setCoefficient(y[start], sGraph.getPrize(startNode));
        objective.setCoefficient(y[finish], sGraph.getPrize(finishNode));
        for (int i = 1; i < finish; i++) {
            objective.setCoefficient(y[i], sGraph.getPrize(localToGraph[i]));
        }
        objective.setMaximization();

        MPSolver.ResultStatus status = solver.solve();
        if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
            return captureResult("OR-Tools ILP", 0, "solver status " + status + "; using direct start->finish path");
        }

        ArrayList<Integer> bestRoute = reconstructOrToolsRoute(x, localToGraph, start, finish);
        double bestDistance = routeDistance(bestRoute);

        total_wt = bestDistance;
        route = bestRoute;
        total_prize = prizeForRoute(route);
        remainingBudget = budget - total_wt;

        String optimality = (status == MPSolver.ResultStatus.OPTIMAL) ? "OPTIMAL" : "FEASIBLE";
        String scope = (k == feasibleCount)
                ? "all " + k + " feasible towns"
                : k + " selected candidates of " + feasibleCount + " feasible";
        return captureResult("OR-Tools ILP", 0, optimality + " over " + scope);
    }

    static ArrayList<Integer> reconstructOrToolsRoute(MPVariable[][] x, int[] localToGraph, int start, int finish) {
        ArrayList<Integer> bestRoute = new ArrayList<>();
        boolean[] usedLocal = new boolean[localToGraph.length];
        int cur = start;
        bestRoute.add(localToGraph[cur]);
        usedLocal[cur] = true;

        while (cur != finish) {
            int next = -1;
            for (int j = 0; j < localToGraph.length; j++) {
                if (x[cur][j] != null && x[cur][j].solutionValue() > 0.5) {
                    next = j;
                    break;
                }
            }
            if (next < 0 || usedLocal[next]) {
                break;
            }
            bestRoute.add(localToGraph[next]);
            usedLocal[next] = true;
            cur = next;
        }

        if (bestRoute.get(bestRoute.size() - 1) != localToGraph[finish]) {
            bestRoute.add(localToGraph[finish]);
        }
        return bestRoute;
    }

    static double routeDistance(ArrayList<Integer> path) {
        double d = 0.0;
        for (int i = 0; i < path.size() - 1; i++) {
            d += sGraph.shortestPath(path.get(i), path.get(i + 1));
        }
        return d;
    }

    /**
     * Trims a candidate list to the top-{@code limit} nodes using the same
     * prize/ratio ranking used by chooseIlpCandidates.  Called only when the
     * full feasible set exceeds MAX_BITMASK_K.
     */
    static ArrayList<Integer> trimCandidates(ArrayList<Integer> candidates, int limit) {
        if (candidates.size() <= limit) return candidates;

        ArrayList<Integer> byPrize = new ArrayList<>(candidates);
        byPrize.sort(Comparator.comparingInt((Integer o) -> sGraph.getPrize(o)).reversed());

        ArrayList<Integer> byRatio = new ArrayList<>(candidates);
        byRatio.sort((a, b) -> Double.compare(candidateRatioScore(b), candidateRatioScore(a)));

        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        int prizeQuota = Math.max(1, limit / 2);
        addTopCandidates(selected, byPrize, prizeQuota);
        addTopCandidates(selected, byRatio, limit);
        return new ArrayList<>(selected);
    }

    static ArrayList<Integer> chooseIlpCandidates(int limit) {
        ArrayList<Integer> feasible = new ArrayList<>();
        int finish = sGraph.getLastNode();
        for (int i = 1; i < finish; i++) {
            if (sGraph.shortestPath(0, i) + sGraph.shortestPath(i, finish) <= budget) {
                feasible.add(i);
            }
        }

        if (limit == 0) {
            return new ArrayList<>();
        }
        if (feasible.size() <= limit) {
            return feasible;
        }

        ArrayList<Integer> byPrize = new ArrayList<>(feasible);
        byPrize.sort(Comparator.comparingInt((Integer o) -> sGraph.getPrize(o)).reversed());

        ArrayList<Integer> byRatio = new ArrayList<>(feasible);
        byRatio.sort((a, b) -> Double.compare(candidateRatioScore(b), candidateRatioScore(a)));

        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        int prizeQuota = Math.max(1, limit / 2);
        addTopCandidates(selected, byPrize, prizeQuota);
        addTopCandidates(selected, byRatio, limit);

        return new ArrayList<>(selected);
    }

    static void addTopCandidates(LinkedHashSet<Integer> selected, ArrayList<Integer> sorted, int limit) {
        for (int node : sorted) {
            if (selected.size() >= limit) {
                break;
            }
            selected.add(node);
        }
    }

    static double candidateRatioScore(int node) {
        int finish = sGraph.getLastNode();
        double pathCost = sGraph.shortestPath(0, node) + sGraph.shortestPath(node, finish);
        if (pathCost <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return sGraph.getPrize(node) / pathCost;
    }

    static int countFeasibleIntermediateNodes() {
        int count = 0;
        int finish = sGraph.getLastNode();
        for (int i = 1; i < finish; i++) {
            if (sGraph.shortestPath(0, i) + sGraph.shortestPath(i, finish) <= budget) {
                count++;
            }
        }
        return count;
    }

    static int prizeForRoute(ArrayList<Integer> path) {
        int prize = 0;
        boolean[] counted = new boolean[statesCt];
        for (int node : path) {
            if (!counted[node]) {
                counted[node] = true;
                prize += sGraph.getPrize(node);
            }
        }
        return prize;
    }

    static int countUnique(ArrayList<Integer> path) {
        if (path == null) {
            return 0;
        }
        boolean[] seen = new boolean[statesCt];
        int count = 0;
        for (int node : path) {
            if (node >= 0 && node < statesCt && !seen[node]) {
                seen[node] = true;
                count++;
            }
        }
        return count;
    }

    static String routeToNames(ArrayList<Integer> path) {
        if (path == null || path.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                sb.append(" -> ");
            }
            int node = path.get(i);
            sb.append(arrCities.get(node).name);
        }
        return sb.toString();
    }

    static void printRunResult(AlgoResult result) {
        System.out.printf(
                "  %-13s reward %,8d | distance %10.2f / %.2f | visited %4d | time %5d ms | %s%n",
                result.name,
                result.reward,
                result.distance,
                budget,
                result.visited,
                result.timeMs,
                result.note);
    }

    static void printComparisonSummary(String[] starts, String[] ends, AlgoResult[][] results) {
        System.out.println("============================== COMPARISON SUMMARY ==============================");
        System.out.printf("%-4s %-10s %-10s %12s %12s %12s %12s%n",
                "Run", "Start", "End", "Greedy-P", "Greedy-R", "PMARL", "ILP/exact");
        System.out.println("--------------------------------------------------------------------------------");

        for (int i = 0; i < results.length; i++) {
            System.out.printf("%-4d %-10s %-10s %12s %12s %12s %12s%n",
                    i + 1,
                    starts[i],
                    ends[i],
                    rewardDistance(results[i][0]),
                    rewardDistance(results[i][1]),
                    rewardDistance(results[i][2]),
                    rewardDistance(results[i][3]));
        }

        System.out.println("--------------------------------------------------------------------------------");
        for (int alg = 0; alg < results[0].length; alg++) {
            double rewardSum = 0.0;
            double distanceSum = 0.0;
            double visitedSum = 0.0;
            double timeSum = 0.0;
            int bestCount = 0;

            for (int run = 0; run < results.length; run++) {
                AlgoResult r = results[run][alg];
                rewardSum += r.reward;
                distanceSum += r.distance;
                visitedSum += r.visited;
                timeSum += r.timeMs;

                int bestReward = Integer.MIN_VALUE;
                for (AlgoResult candidate : results[run]) {
                    bestReward = Math.max(bestReward, candidate.reward);
                }
                if (r.reward == bestReward) {
                    bestCount++;
                }
            }

            System.out.printf(
                    "%-13s avg reward %,10.2f | avg dist %10.2f | avg visited %7.2f | avg time %8.2f ms | best/tied %d/%d%n",
                    results[0][alg].name,
                    rewardSum / results.length,
                    distanceSum / results.length,
                    visitedSum / results.length,
                    timeSum / results.length,
                    bestCount,
                    results.length);
        }
    }

    static String rewardDistance(AlgoResult result) {
        return String.format("%,d/%.0f", result.reward, result.distance);
    }

    static void printSummary(String[] starts, String[] ends, int[] rewards, double[] dists, int[] visited) {
        System.out.println("============================== SUMMARY ==============================");
        System.out.printf("%-5s %-10s %-10s %8s %12s %8s%n", "Run", "Start", "End", "Reward", "Distance", "Visited");
        System.out.println("---------------------------------------------------------------------");

        int sumReward = 0;
        double sumDist = 0.0;
        int sumVisited = 0;
        int bestRun = 0;

        for (int i = 0; i < rewards.length; i++) {
            System.out.printf("%-5d %-10s %-10s %,8d %12.2f %8d%n",
                    i + 1, starts[i], ends[i], rewards[i], dists[i], visited[i]);
            sumReward += rewards[i];
            sumDist += dists[i];
            sumVisited += visited[i];
            if (rewards[i] > rewards[bestRun]) {
                bestRun = i;
            }
        }

        System.out.println("---------------------------------------------------------------------");
        System.out.printf("%-5s %-10s %-10s %,8.2f %12.2f %8.2f%n",
                "AVG", "", "",
                sumReward / (double) rewards.length,
                sumDist / rewards.length,
                sumVisited / (double) visited.length);
        System.out.printf("Best reward: run %d (%s -> %s), reward %,d%n",
                bestRun + 1, starts[bestRun], ends[bestRun], rewards[bestRun]);
    }
}