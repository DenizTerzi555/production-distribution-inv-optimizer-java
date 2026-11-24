package cbam;
import cbam.Data.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class CsvLoader {
    private CsvLoader(){}
    /** One CSV file as a list of rows; each row maps column name -> text */
    static List<Map<String, String>> readTable (Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        if (lines.isEmpty()) throw new IOException("Empty file: " + file);
        String headerLine = lines.get(0).replace("\uFEFF", "");
        String sep = headerLine.contains(";") ? ";" : ",";
        String[] header = headerLine.split(sep);
        for (int k = 0; k < header.length; k++) header[k] = header[k].trim();
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;   // skip blank lines and comments
            String[] cells = line.split(sep);
            if (cells.length != header.length)
                throw new IOException(file.getFileName() + " line " + (i + 1) + ": expected "
                    + header.length + " columns but found " + cells.length);
            Map<String, String> row = new LinkedHashMap<>();
            for (int k = 0; k < header.length; k++) row.put(header[k], cells[k].trim());
            rows.add(row);
        }
        return rows;
    }
    private static double num(Map<String, String> row, String column) {
        String text = row.get(column);
        if (text == null) throw new IllegalArgumentException("Missing column '" + column + "'");
        try {
            return Double.parseDouble(text.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Column '" + column + "' is not a number: '" + text + "'");
        }
    }
    private static int indexOf(Map<String, Integer> names, String name, String where) {
        Integer i = names.get(name);
        if (i == null) throw new IllegalArgumentException("Unknown name '" + name + "' in " + where);
        return i;
    }
    private static double param(Map<String, Double> kv, String key) {
        Double v = kv.get(key);
        if (v == null) throw new IllegalArgumentException("params.csv: missing key '" + key + "'");
        return v;
    }
    private static boolean bool(Map<String, String> row, String column) {
        String t = row.get(column);
        if (t == null) throw new IllegalArgumentException("Missing column '" + column + "'");
        t = t.toLowerCase();
        if (t.equals("true") || t.equals("yes")) return true;
        if (t.equals("false") || t.equals("no")) return false;
        throw new IllegalArgumentException("Column '" + column + "' is not true / false '" + t + "'");
    }
    public static ProblemData load(Path dir) throws IOException {
        // ---- the four "entity" tables ----
        List<Facility> facilities = new ArrayList<>();
        for (var r: readTable(dir.resolve("facilities.csv")))
            facilities.add(new Facility(r.get("name"), num(r, "capHoursPerPeriod"), num(r, "storageCapTons")));

        List<Product> products = new ArrayList<>();
        for (var r : readTable(dir.resolve("products.csv")))
            products.add(new Product(r.get("name"), num(r, "hoursPerTon"), num(r, "prodEmissionTPerT"),
                    num(r, "holdingCostPerTPerPeriod")));

        List<Customer> customers = new ArrayList<>();
        for (var r : readTable(dir.resolve("customers.csv")))
            customers.add(new Customer(r.get("name"), bool(r, "euBound")));

        List<Mode> modes = new ArrayList<>();
        for (var r : readTable(dir.resolve("modes.csv")))
            modes.add(new Mode(r.get("name"), num(r, "costPerTonKm"), num(r, "emissionKgPerTonKm")));

        // --- nane -> position lookups
        Map<String, Integer> fi = new HashMap<>(), pi = new HashMap<>(), ci = new HashMap<>(), mi = new HashMap<>();
        for (int i = 0; i < facilities.size(); i++) fi.put(facilities.get(i).name(), i);
        for (int i = 0; i < products.size(); i++) pi.put(products.get(i).name(), i);
        for (int i = 0; i < customers.size(); i++) ci.put(customers.get(i).name(), i);
        for (int i = 0; i < modes.size(); i++) mi.put(modes.get(i).name(), i);
        int F = facilities.size(), P = products.size(), C = customers.size(), M = modes.size();

        // ----- parameters -----
        Map<String, Double> kv = new HashMap<>();
        for (var r : readTable(dir.resolve("params.csv"))) kv.put(r.get("key"), num(r, "value"));
        int periods = (int) Math.round(param(kv, "periods"));
        Params params = new Params(periods, param(kv, "carbonTax"), param(kv, "allowance"),
                param(kv, "creditSellPrice"), param(kv, "creditBuyPrice"),
                        kv.getOrDefault("creditBuyCap", Double.POSITIVE_INFINITY));

        // ----- the lookup tables -----
        double[][] prodCost = new double[F][P];
        for (var r : readTable(dir.resolve("prodcost.csv")))
            prodCost[indexOf(fi, r.get("facility"), "prodcost.csv")][indexOf(pi, r.get("product"), "prodcost.csv")]
                    = num(r, "costPerTon");
        double[][] price = new double[C][P];
        for(var r : readTable(dir.resolve("prices.csv")))
            price[indexOf(ci, r.get("customer"), "prices.csv")][indexOf(pi, r.get("product"), "prices.csv")]
                    = num(r, "pricePerTon");

        double[][][] demand = new double[C][P][periods];
        for (var r : readTable(dir.resolve("demand.csv"))) {
            int t = (int) Math.round(num(r, "period"));
            if (t < 0 || t >= periods)
                throw new IllegalArgumentException("demand.csv: period " + t + " outside 0.." + (periods - 1));
            demand[indexOf(ci, r.get("customer"), "demand.csv")][indexOf(pi, r.get("product"), "demand.csv")][t]
                    = num(r, "tons");
        }
        double[][] dist = new double[F][C];
        for (var r : readTable(dir.resolve("distances.csv")))
            dist[indexOf(fi, r.get("facility"), "distances.csv")][indexOf(ci, r.get("customer"), "distances.csv")]
                    = num(r, "km");

        double[][] modeCap = new double[F][M];
        for (var r : readTable(dir.resolve("modecap.csv")))
            modeCap[indexOf(fi, r.get("facility"), "modecap.csv")][indexOf(mi, r.get("mode"), "modecap.csv")]
                    = num(r, "capPerPeriod");

        boolean[][] allowed = new boolean[P][M];
        for (boolean[] row : allowed) Arrays.fill(row, true);
        Path allowedFile = dir.resolve("modeallowed.csv");
        if (Files.exists(allowedFile)) {
            for (var r : readTable(allowedFile))
                allowed[indexOf(pi, r.get("product"), "modeallowed.csv")][indexOf(mi, r.get("mode"), "modeallowed.csv")]
                        =bool(r, "allowed");
        }
        return new ProblemData(facilities, products, customers, modes, prodCost, price, demand,
                dist, modeCap, allowed, params);
    }
}
