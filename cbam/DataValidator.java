package cbam;
import cbam.Data.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sanity checks for a data set BEFORE it is given to the solver
 * Bad data is the most common reason for strange results, so it is worth catching early.
 */

public final class DataValidator {
    private DataValidator(){}
    public static List<String> validate(ProblemData d) {
        List<String> out = new ArrayList<>();
        int F = d.facilities().size(), C = d.customers().size(), P = d.products().size();
        int M = d.modes().size(), T = d.params().periods();
        // ----- 1) Impossible numbers
        for (int i = 0; i < F; i++) {
            Facility f = d.facilities().get(i);
            if (f.capHoursPerPeriod() <=0) out.add("ERROR   facility " + f.name() + " has no production capacity.");
            if (f.storageCapTons() < 0)    out.add("ERROR   facility " + f.name() + " has negative storage capacity.");
            for (int p = 0; p < P; p++)
                if (d.prodCost()[i][p] <= 0)
                    out.add("ERROR  missing or non positive production cost: " + f.name() + " / " + d.products().get(p).name());
            for (int j = 0; j < C; j++)
                if (d.distanceKm()[i][j] <= 0)
                    out.add("ERROR  missing or non positive distance: " + f.name() + " -> " + d.customers().get(j).name());
            for (int m = 0; m < M; m++)
                if (d.modeCap()[i][m] < 0) out.add("ERROR   negative mode capacity at " + f.name());
        }
        // -------- 2) Every product that is demanded must have a price and an allowed mode
        for (int p = 0; p < P; p++) {
            boolean anyMode = false;
            for (int m = 0; m < M; m++) anyMode |= d.modeAllowed()[p][m];
            if (!anyMode) out.add("ERROR    product " + d.products().get(p).name() + " has no allowed transport mode");
            for (int j = 0; j < C; j++) {
                double totalDemand = 0;
                for (int t = 0; t < T; t++) totalDemand += d.demand()[j][p][t];
                if (totalDemand > 0 && d.price()[j][p] <= 0)
                    out.add("ERROR  demand exists but the price is missing: " + d.customers().get(j).name() + " / " + d.products().get(p).name());
                if (totalDemand < 0) out.add("ERROR    negative demand for " + d.customers().get(j).name());
            }
        }
        // --------- 3) Is a sale profitable at all?
        for (int j = 0; j < C; j++) for (int p = 0; p < P; p++) {
            if (d.price()[j][p] <= 0) continue;
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < F; i++) for (int m = 0; m < M; m++) {
                if (!d.modeAllowed()[p][m]) continue;
                best = Math.min(best, d.prodCost()[i][p] + d.modes().get(m).costPerTonKm() * d.distanceKm()[i][j]);
            }
            if (best >= d.price()[j][p])
                out.add(String.format(Locale.US, "WARNING %s / %s is unprofitable even before carbon costs (price %.0f, cheapest cost %.0f)",
                        d.customers().get(j).name(), d.products().get(p).name(), d.price()[j][p], best));
        }
        // -------- 4) Does capacity even come close to demand?
        double capHours = 0;
        for (Facility f : d.facilities()) capHours += f.capHoursPerPeriod();
        for (int t = 0; t < T; t++) {
            double needHours = 0, needTons = 0;
            for (int j = 0; j < C; j++) for (int p = 0; p < P; p++) {
                needHours += d.demand()[j][p][t] * d.products().get(p).hoursPerTon();
                needTons += d.demand()[j][p][t];
            }
            if (needHours > capHours * 1.0001)
                out.add(String.format(Locale.US, "INFO  period %d: demand needs %.0f production hours but capacity is %.0f (capacity binds)",
                        t, needHours, capHours));
            double modeTons = 0;
            for (int i = 0; i < F; i++) for (int m = 0; m < M; m++) modeTons += d.modeCap()[i][m];
            if (needTons > modeTons * 1.0001)
                out.add(String.format(Locale.US, "INFO  period %d: demand is %.0f t but total transport capacity is %.0f t", t, needTons, modeTons));
        }
        // ------ 5) Carbon settings
        boolean anyEu = false;
        for (Customer c : d.customers()) anyEu |= c.euBound();
        if (!anyEu && d.params().carbonTax() > 0)
            out.add("WARNING    carbon tax is positive but no customer region is marked euBound = true, so it will never be charged.");
        if (d.params().allowance() <= 0)
            out.add("WARNING    allowance is zero: every ton of CO2 must be covered by purchase credits.");
        long errors = out.stream().filter(s -> s.startsWith("ERROR")).count();
        if (out.isEmpty()) out.add("OK   no problems encountered.");
        else if (errors == 0) out.add(0, "OK     no errors (see notes below).");
        return out;
    }
    public static boolean hasErrors(List<String> messages) {
        return messages.stream().anyMatch(s -> s.startsWith("ERROR"));
    }
}
