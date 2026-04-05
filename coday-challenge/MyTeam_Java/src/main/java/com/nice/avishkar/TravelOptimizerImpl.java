package com.nice.avishkar;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.*;

public class TravelOptimizerImpl implements ITravelOptimizer {

    private boolean generateSummary;

    public TravelOptimizerImpl(boolean generateSummary) {
        this.generateSummary = generateSummary;
    }

    // Main

    public Map<String, OptimalTravelSchedule> getOptimalTravelOptions(ResourceInfo resourceInfo)
            throws IOException {

        Map<String, OptimalTravelSchedule> result = new LinkedHashMap<>();

        List<String> scheduleLines = Files.readAllLines(resourceInfo.getTransportSchedulePath());
        List<String> requestLines  = Files.readAllLines(resourceInfo.getCustomerRequestPath());

        Map<String, List<Edge>> graph = buildGraph(scheduleLines);

        for (int i = 1; i < requestLines.size(); i++) {

            String[] parts = requestLines.get(i).split(",");

            String requestId   = parts[0].trim();
            String source      = parts[2].trim();
            String destination = parts[3].trim();
            String criteria    = parts[4].trim();

            if (source.equals(destination)) {
                result.put(requestId,
                        new OptimalTravelSchedule(
                                new ArrayList<>(), criteria, 0,
                                generateSummary ? "No routes available" : "Not generated"));
                continue;
            }

            int mode = getMode(criteria);
            Result best = dijkstra(graph, source, destination, mode);

            if (best.path.isEmpty()) {
                result.put(requestId,
                        new OptimalTravelSchedule(
                                new ArrayList<>(), criteria, 0,
                                generateSummary ? "No routes available" : "Not generated"));
                continue;
            }

            List<Route> routes = new ArrayList<>();
            for (Edge e : best.path) {
                routes.add(new Route(e.source, e.destination, e.modeStr, e.depStr, e.arrStr));
            }

            long value = (mode == 0) ? best.time : (mode == 1) ? best.cost : best.hops;

            String summary;
            if (!generateSummary) {
                summary = "Not generated";
            } else {
                summary = generateAISummary(routes, best);
            }

            result.put(requestId,
                    new OptimalTravelSchedule(routes, criteria, value, summary));
        }

        return result;
    }

    // Graph

    private Map<String, List<Edge>> buildGraph(List<String> lines) {
        Map<String, List<Edge>> graph = new HashMap<>();

        for (int i = 1; i < lines.size(); i++) {
            String[] p = lines.get(i).split(",");

            String source = p[0].trim();
            String dest   = p[1].trim();
            String mode   = p[2].trim();
            String dep    = p[3].trim();
            String arr    = p[4].trim();
            int cost      = Integer.parseInt(p[5].trim());

            graph.computeIfAbsent(source, k -> new ArrayList<>())
                    .add(new Edge(source, dest, mode, dep, arr, cost));
        }

        return graph;
    }

    // Dijkstra

    private Result dijkstra(Map<String, List<Edge>> graph,
                            String src,
                            String dst,
                            int mode) {

        PriorityQueue<State> pq = new PriorityQueue<>((a, b) -> compare(a, b, mode));
        pq.add(new State(src, 0, 0, 0, -1, -1, null, null));

        Map<String, Integer> visited = new HashMap<>();

        while (!pq.isEmpty()) {

            State curr = pq.poll();

            int primaryMetric = (mode == 0) ? curr.time : (mode == 1) ? curr.cost : curr.hops;

            if (visited.containsKey(curr.node) && visited.get(curr.node) <= primaryMetric) continue;
            visited.put(curr.node, primaryMetric);

            if (curr.node.equals(dst)) return reconstruct(curr);

            List<Edge> list = graph.get(curr.node);
            if (list == null) continue;

            for (Edge e : list) {

                int dep = e.depMin;
                int arr = e.arrMin;

                if (curr.arrivalMin != -1) {

                    int normalizedDep = dep;
                    if (normalizedDep < curr.arrivalMin) {
                        int diff = curr.arrivalMin - normalizedDep;
                        normalizedDep += ((diff / 1440) + 1) * 1440;
                    }
                    if (e.arrMin < e.depMin) {
                        arr = normalizedDep + (e.arrMin + 1440 - e.depMin);
                    } else {
                        arr = normalizedDep + (e.arrMin - e.depMin);
                    }
                    dep = normalizedDep;
                } else {
                    if (e.arrMin < e.depMin) {
                        arr = e.arrMin + 1440;
                    }
                }

                int newCost = curr.cost + e.cost;
                int newHops = curr.hops + 1;
                int start = (curr.startTime == -1) ? dep : curr.startTime;
                int newTime = arr - start;

                pq.add(new State(
                        e.destination,
                        newTime,
                        newCost,
                        newHops,
                        arr,
                        start,
                        e,
                        curr
                ));
            }
        }

        return new Result(new ArrayList<>(), 0, 0, 0);
    }

    private Result reconstruct(State s) {
        LinkedList<Edge> path = new LinkedList<>();
        State curr = s;

        while (curr.edgeUsed != null) {
            path.addFirst(curr.edgeUsed);
            curr = curr.parent;
        }

        return new Result(path, s.time, s.cost, s.hops);
    }

    // Summary

    private String buildSummary(List<Route> routes, Result best) {
        StringBuilder sb = new StringBuilder();

        for (Route r : routes) {
            sb.append(r.getSource()).append(r.getDestination());
        }

        sb.append(best.time).append(best.cost).append(best.hops);

        return sb.toString();
    }

    // AI SUMMARY

    private String generateAISummary(List<Route> routes, Result best) {
        try {
            String token = "REMOVEDFaEfTcXVKtcnjLiAIUEfYCKHFoXYFNnuJs";  //hugging face token

            String prompt = buildPrompt(routes, best);

            String apiUrl = "https://router.huggingface.co/hf-inference/models/facebook/bart-large-cnn";

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            String escapedPrompt = prompt.replace("\\", "\\\\").replace("\"", "\\\"");
            String body = "{"
                    + "\"inputs\": \"" + escapedPrompt + "\","
                    + "\"parameters\": {"
                    + "\"max_length\": 80,"
                    + "\"min_length\": 20"
                    + "}"
                    + "}";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes());
            }

            int responseCode = conn.getResponseCode();
            InputStream is = (responseCode == 200) ? conn.getInputStream() : conn.getErrorStream();

            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }

            String json = response.toString().trim();


            // Bart response format
            if (json.contains("summary_text")) {
                int start = json.indexOf("\"summary_text\"") + "\"summary_text\"".length();
                start = json.indexOf("\"", start) + 1;
                int end = json.lastIndexOf("\"");
                if (start > 0 && end > start) {
                    String extracted = json.substring(start, end).trim();
                    // Trim to 60 words max
                    String[] words = extracted.split("\\s+");
                    if (words.length > 60) {
                        StringBuilder trimmed = new StringBuilder();
                        for (int i = 0; i < 60; i++) {
                            if (i > 0) trimmed.append(" ");
                            trimmed.append(words[i]);
                        }
                        return trimmed.toString();
                    }
                    return extracted;
                }
            }

            return "AI summary generation failed";

        } catch (Exception e) {
            e.printStackTrace();
            return "AI summary generation failed";
        }
    }

    private String buildPrompt(List<Route> routes, Result best) {
        Route first = routes.get(0);
        Route last  = routes.get(routes.size() - 1);

        StringBuilder hops = new StringBuilder();
        for (int i = 0; i < routes.size(); i++) {
            Route r = routes.get(i);
            if (i > 0) hops.append(" -> ");
            hops.append(r.getSource());
        }
        hops.append(" -> ").append(last.getDestination());

        return "Travel from " + first.getSource() + " to " + last.getDestination()
                + " via " + hops + ". Departing at " + first.getDepartureTime()
                + ", arriving at " + last.getArrivalTime()
                + ". Total travel time: " + best.time + " minutes."
                + " Total cost: Rs " + best.cost + "."
                + " Number of stops: " + best.hops + ".";
    }

    // helpers

    private int getMode(String c) {
        if (c.equalsIgnoreCase("time")) return 0;
        if (c.equalsIgnoreCase("cost")) return 1;
        return 2;
    }

    private int compare(State a, State b, int mode) {
        if (mode == 0) {
            if (a.time != b.time) return a.time - b.time;
            if (a.cost != b.cost) return a.cost - b.cost;
            return a.hops - b.hops;
        }
        if (mode == 1) {
            if (a.cost != b.cost) return a.cost - b.cost;
            if (a.time != b.time) return a.time - b.time;
            return a.hops - b.hops;
        }
        if (a.hops != b.hops) return a.hops - b.hops;
        if (a.time != b.time) return a.time - b.time;
        return a.cost - b.cost;
    }

    // internal classes

    static class Edge {
        String source, destination, modeStr, depStr, arrStr;
        int depMin, arrMin, cost;

        Edge(String s, String d, String m, String dep, String arr, int c) {
            source = s;
            destination = d;
            modeStr = m;
            depStr = dep;
            arrStr = arr;
            cost = c;

            depMin = toMinutes(dep);
            arrMin = toMinutes(arr);
        }

        private int toMinutes(String t) {
            String[] s = t.split(":");
            return Integer.parseInt(s[0]) * 60 + Integer.parseInt(s[1]);
        }
    }

    static class State {
        String node;
        int time, cost, hops;
        int arrivalMin, startTime;
        Edge edgeUsed;
        State parent;

        State(String n, int t, int c, int h, int a, int start, Edge e, State p) {
            node = n;
            time = t;
            cost = c;
            hops = h;
            arrivalMin = a;
            startTime = start;
            edgeUsed = e;
            parent = p;
        }
    }

    static class Result {
        List<Edge> path;
        int time, cost, hops;

        Result(List<Edge> p, int t, int c, int h) {
            path = p;
            time = t;
            cost = c;
            hops = h;
        }
    }
}