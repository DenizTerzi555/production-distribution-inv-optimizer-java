package cbam;
import java.util.List;

/** All input data of te planning problem, as immutable records. */

public final class Data {
    private Data() {}   // this class is only a namespace for records below
    public record Facility (String name, double capHoursPerPeriod, double storageCapTons) {}
    public record Product(String name, double hoursPerTon, double prodEmissionTPerT, double holdingCostPerTPerPeriod) {}
    public record Customer(String name, boolean euBound) {}
    public record Mode(String name, double costPerTonKm, double emissionKgPerTonKm) {}

    /** Scalar settings. Records are immutable, so "changing" a value means creating a modified copy. */

    public record Params(int periods, double carbonTax, double allowance, double creditSellPrice, double creditBuyPrice, double creditBuyCap) {
        public Params {
            if (periods < 1) throw new IllegalArgumentException("periods must be >= 1");
            if (creditBuyPrice < creditSellPrice)
                throw new IllegalArgumentException("buy price must be >= sell price, otherwise the LP is unbounded");
            if (creditBuyCap < 0)
                throw new IllegalArgumentException("creditBuyCap must be >= 0 (use Infinity for no limit)");
        }
        public Params withCarbonTax(double v) {
            return new Params(periods, v, allowance, creditSellPrice, creditBuyPrice, creditBuyCap);
        }
        public Params withAllowance(double v) {
            return new Params(periods, carbonTax, v, creditSellPrice, creditBuyPrice, creditBuyCap);
        }
        public Params withCreditBuyCap(double v) {
            return new Params(periods, carbonTax, allowance, creditSellPrice, creditBuyPrice, v);
        }
    }

    /**
     * The complete data set. ProblemData is the big bag passed to the model.
     */
    public record ProblemData(
            List<Facility> facilities, List<Product> products, List<Customer> customers, List<Mode> modes,
            double[][] prodCost,        // [facility][product]
            double[][] price,           // [customer][product]
            double[][][] demand,        // [customer][product][period]
            double[][] distanceKm,      // [facility][customer]
            double[][] modeCap,         // [facility][mode]
            boolean[][] modeAllowed,    // [product][mode]
            Params params) {
        public ProblemData withParams(Params p) {
            return new ProblemData(facilities, products, customers, modes, prodCost, price, demand,
                    distanceKm, modeCap, modeAllowed, p);
        }
        /** Copy with the capacity of one mode at one facility changed. Arrays are copied so the original stays untouched. */
        public ProblemData withModeCap(int facility, int mode, double value) {
            double[][] copy = new double[modeCap.length][];
            for (int i = 0; i < modeCap.length; i++) copy[i] = modeCap[i].clone();
            copy[facility][mode] = value;
            return new ProblemData(facilities, products, customers, modes, prodCost, price, demand,
                    distanceKm, copy, modeAllowed, params);
        }
        /** Copy in which one product may (or may not) use one mode */
        public ProblemData withModeAllowed(int product, int mode, boolean allowed) {
            boolean[][] copy = new boolean[modeAllowed.length][];
            for (int p = 0; p < modeAllowed.length; p++) copy[p] = modeAllowed[p].clone();
            copy[product][mode] = allowed;
            return new ProblemData(facilities, products, customers, modes, prodCost, price, demand,
                    distanceKm, modeCap, copy, params);
        }
        /** Emission in tC02 of moving one tn of product p on lane (i -> j) by mode m, including production */
        public double embeddedPlusRouteEmission(int i, int j, int p, int m) {
            return products.get(p).prodEmissionTPerT()
                    + modes.get(m).emissionKgPerTonKm() * distanceKm[i][j] / 1000.0;
        }
        /** Emission in tC02 caused only by transport of one ton on lane (i -> j) by mode m. */
        public double routeEmission(int i, int j, int m) {
            return modes.get(m).emissionKgPerTonKm() * distanceKm[i][j] / 1000.0;
        }
    }
}
