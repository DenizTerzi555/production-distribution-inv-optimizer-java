package cbam;

import javax.swing.*;
import java.awt.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.text.DecimalFormat;

public class Gui extends JFrame {
    private final JTextField folderField = new JTextField("data/fulldata", 30);
    private final JLabel datasetLabel = new JLabel("No data loaded.");
    private final JTextField taxField = new JTextField(10), allowField = new JTextField(10),
            sellField = new JTextField(10), buyField = new JTextField(10), capField = new JTextField(10);
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel headline = new JLabel(" ");
    private final JPanel resultsBody = new JPanel(new GridLayout(2, 1, 8, 8));
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTabbedPane planTabs = new JTabbedPane();
    private final JComboBox<String> sweepParam = new JComboBox<>(new String[]{
            "Carbon tax (EUR/tCO2)", "Emission allowance (tCO2)", "Credit purchase cap (% of allowance)"});
    private final JTextField fromField = new JTextField("0", 7), toField = new JTextField("200", 7),
            stepsField = new JTextField("9", 5);
    private final JCheckBox unlimitedBox = new JCheckBox("add a 'no limit' point (credit cap only)");
    private final JButton sweepButton = new JButton("Run sweep");
    private final JLabel sweepStatus = new JLabel(" ");
    private final JPanel sweepTablePanel = new JPanel(new BorderLayout());
    private final JButton saveSweepButton = new JButton("Save sweep as CSV...");
    private final JComboBox<String> metricBox = new JComboBox<>(new String[] {
            "Profit (EUR)", "Emissions t(CO2)", "Rail share (%)", "Credits bought (tCO2)", "Credits sold (tCO2)"});
    private final ChartPanel chart = new ChartPanel();
    private List<ResultTables.SweepRow> lastSweep = new ArrayList<>();
    private ResultTables.Table lastSweepTable;
    private PlanModel.Plan lastPlan;
    private Data.ProblemData baseData;

    public Gui() {
        super("Carbon-aware planner");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(6, 6));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton load = new JButton("Load data");
        top.add(new JLabel("Data folder:"));
        top.add(folderField);
        top.add(load);
        add(top, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(new JLabel("Carbon tax (EUR/tCO2)"));              form.add(taxField);
        form.add(new JLabel("Emission allowance (tCO2)"));          form.add(allowField);
        form.add(new JLabel("Credit sell price"));                  form.add(sellField);
        form.add(new JLabel("Credit buy price"));                   form.add(buyField);
        form.add(new JLabel("Credit purchase cap (empty = none)")); form.add(capField);
        JButton solve = new JButton("Solve");
        form.add(solve);
        JPanel west = new JPanel(new BorderLayout());
        west.add(form, BorderLayout.NORTH);
        add(west, BorderLayout.WEST);

        JPanel center = new JPanel(new BorderLayout(6, 6));
        JPanel info = new JPanel(new GridLayout(2, 1));
        info.add(datasetLabel);
        info.add(headline);
        center.add(info, BorderLayout.NORTH);
        center.add(resultsBody, BorderLayout.CENTER);
        tabs.addTab("Results", center);
        tabs.addTab("Plan", buildPlanTab());
        tabs.addTab("Scenarios", buildSweepTab());
        add(tabs, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        load.addActionListener(e -> loadData());
        solve.addActionListener(e -> solve());
        saveSweepButton.setEnabled(false);
        setSize(1100, 750);
        setLocationRelativeTo(null);
    }

    private void loadData() {
        try {
            baseData = CsvLoader.load(Path.of(folderField.getText().trim()));
            datasetLabel.setText(baseData.facilities().size() + " facilities, "
                    + baseData.customers().size() + " customers, "
                    + baseData.products().size() + " products, "
                    + baseData.modes().size() + " modes, "
                    + baseData.params().periods() + " periods");
            Data.Params p = baseData.params();
            taxField.setText(String.valueOf(p.carbonTax()));
            allowField.setText(String.valueOf(p.allowance()));
            sellField.setText(String.valueOf(p.creditSellPrice()));
            buyField.setText(String.valueOf(p.creditBuyPrice()));
            capField.setText(Double.isInfinite(p.creditBuyCap()) ? "" : String.valueOf(p.creditBuyCap()));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not load data: " + ex.getMessage());
        }
    }

    private void solve() {
        try {
            double tax = Double.parseDouble(taxField.getText().trim());
            double allow = Double.parseDouble(allowField.getText().trim());
            double sell = Double.parseDouble(sellField.getText().trim());
            double buy = Double.parseDouble(buyField.getText().trim());
            double cap = capField.getText().trim().isEmpty()
                    ? Double.POSITIVE_INFINITY : Double.parseDouble(capField.getText().trim());
            Data.Params params = new Data.Params(baseData.params().periods(), tax, allow, sell, buy, cap);
            PlanModel.Plan plan = new PlanModel(baseData.withParams(params)).solve();
            if (plan.solution.isOptimal()) {
                statusLabel.setText(String.format("OPTIMAL | profit %,.2f EUR", plan.summary().profit()));
                lastPlan = plan;
                showResults(plan);
            } else
                statusLabel.setText("Status: " + plan.solution.status());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Problem: " + ex.getMessage());
        }
    }

    private void showResults(PlanModel.Plan plan) {
        PlanModel.Plan.Summary s = plan.summary();
        headline.setText(String.format("Profit %,.0f EUR | emissions %,.0f tCO2 (allowance %,.0f)",
                s.profit(), s.emissions(), plan.d.params().allowance()));
        resultsBody.removeAll();
        JPanel top = new JPanel(new GridLayout(1, 2, 8, 8));
        top.add(titled("Profit and loss (EUR)", makeTable(ResultTables.profitAndLoss(plan))));
        top.add(titled("Physical results", makeTable(ResultTables.physical(plan))));
        resultsBody.add(top);
        resultsBody.add(titled("Most valuable constraints (shadow prices)", makeTable(ResultTables.duals(plan, 25))));
        resultsBody.revalidate();
        resultsBody.repaint();

        planTabs.removeAll();
        planTabs.addTab("Shipments", filterable(ResultTables.shipments(plan)));
        planTabs.addTab("Production", filterable(ResultTables.production(plan)));
        planTabs.addTab("Mode by period", new JScrollPane(makeTable(ResultTables.modeByPeriod(plan))));
    }

    private JComponent filterable(ResultTables.Table t) {
        JTable table = makeTable(t);
        TableRowSorter<?> sorter = (TableRowSorter<?>) table.getRowSorter();
        JTextField filter = new JTextField(20);
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void update() {
                String s = filter.getText().trim();
                sorter.setRowFilter(s.isEmpty() ? null
                        : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(s)));
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { update(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { update(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { update(); }
        });
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(new JLabel("Filter (any column):"));
        bar.add(filter);
        bar.add(new JLabel(t.rows().length + " rows. Click a column header to sort."));
        JPanel p = new JPanel(new BorderLayout());
        p.add(bar, BorderLayout.NORTH);
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        return p;
    }

    private JComponent buildSweepTab() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        row1.add(new JLabel("Parameter:"));
        row1.add(sweepParam);
        row1.add(new JLabel("from"));
        row1.add(fromField);
        row1.add(new JLabel("to"));
        row1.add(toField);
        row1.add(new JLabel("points"));
        row1.add(stepsField);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        row2.add(unlimitedBox);
        row2.add(sweepButton);
        row2.add(saveSweepButton);
        row2.add(sweepStatus);
        JPanel ctl = new JPanel(new GridLayout(2, 1));
        ctl.add(row1);
        ctl.add(row2);
        sweepParam.addActionListener(e -> {
            int k = sweepParam.getSelectedIndex();
            if (k == 0) { fromField.setText("0"); toField.setText("200"); stepsField.setText("9"); }
            if (k == 1) { fromField.setText("40000"); toField.setText("65000"); stepsField.setText("6"); }
            if (k == 2) { fromField.setText("0"); toField.setText("16"); stepsField.setText("9"); }
        });
        sweepButton.addActionListener(e -> runSweep());
        JPanel chartBox = new JPanel(new BorderLayout());
        JPanel metricBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        metricBar.add(new JLabel("Chart shows: "));
        metricBar.add(metricBox);
        chartBox.add(metricBar, BorderLayout.NORTH);
        chartBox.add(chart, BorderLayout.CENTER);
        metricBox.addActionListener(e -> refreshChart());
        saveSweepButton.addActionListener(e -> saveSweep());
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sweepTablePanel, chartBox);
        split.setResizeWeight(0.35);
        root.add(ctl, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        return root;
    }

    /** Base data with the parameter fields applied. */
    private Data.ProblemData currentData() {
        double tax = Double.parseDouble(taxField.getText().trim());
        double allow = Double.parseDouble(allowField.getText().trim());
        double sell = Double.parseDouble(sellField.getText().trim());
        double buy = Double.parseDouble(buyField.getText().trim());
        double cap = capField.getText().trim().isEmpty()
                ? Double.POSITIVE_INFINITY : Double.parseDouble(capField.getText().trim());
        return baseData.withParams(new Data.Params(baseData.params().periods(), tax, allow, sell, buy, cap));
    }

    private void runSweep() {
        final Data.ProblemData data;
        final double from, to;
        final int n;
        try {
            data = currentData();
            from = Double.parseDouble(fromField.getText().trim());
            to = Double.parseDouble(toField.getText().trim());
            n = Integer.parseInt(stepsField.getText().trim());
            if (n < 2 || n > 60) throw new IllegalArgumentException("points must be between 2 and 60");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Check the inputs (load data first): " + ex.getMessage());
            return;
        }
        final int kind = sweepParam.getSelectedIndex();
        final boolean addUnlimited = unlimitedBox.isSelected() && kind == 2;
        final String label = (String) sweepParam.getSelectedItem();
        sweepButton.setEnabled(false);
        sweepStatus.setText("Running...");
        new SwingWorker<List<ResultTables.SweepRow>, Integer>() {
            @Override protected List<ResultTables.SweepRow> doInBackground() {
                List<Double> values = new ArrayList<>();
                for (int k = 0; k < n; k++) values.add(from + (to - from) * k / (n - 1));
                if (addUnlimited) values.add(Double.POSITIVE_INFINITY);
                List<ResultTables.SweepRow> rows = new ArrayList<>();
                for (double v : values) {
                    Data.ProblemData d;
                    if (kind == 0) d = data.withParams(data.params().withCarbonTax(v));
                    else if (kind == 1) d = data.withParams(data.params().withAllowance(v));
                    else d = data.withParams(data.params().withCreditBuyCap(
                                Double.isInfinite(v) ? v : data.params().allowance() * v / 100.0));
                    rows.add(ResultTables.solveRow(v, d));
                    publish(rows.size());
                }
                return rows;
            }
            @Override protected void process(List<Integer> chunks) {
                sweepStatus.setText("Solved " + chunks.get(chunks.size() - 1) + " scenarios...");
            }
            @Override protected void done() {
                sweepButton.setEnabled(true);
                try {
                    List<ResultTables.SweepRow> rows = get();
                    lastSweep = rows;
                    lastSweepTable = ResultTables.sweepTable(label, rows);
                    sweepTablePanel.removeAll();
                    sweepTablePanel.add(new JScrollPane(makeTable(lastSweepTable)), BorderLayout.CENTER);
                    sweepTablePanel.revalidate();
                    sweepTablePanel.repaint();
                    sweepStatus.setText("Done: " + rows.size() + " scenarios.");
                    saveSweepButton.setEnabled(true);
                    chart.xLabel = label;
                    refreshChart();
                } catch (Exception ex) {
                    sweepStatus.setText("Error.");
                    JOptionPane.showMessageDialog(Gui.this, "Sweep failed: " + ex.getMessage());
                }
            }
        }.execute();
    }
    private JComponent buildPlanTab() {
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JButton save = new JButton("Save shipments, production and mode tables as CSV...");
        save.addActionListener(e -> savePlanCsv());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(save);
        root.add(bar, BorderLayout.NORTH);
        root.add(planTabs, BorderLayout.CENTER);
        return root;
    }

    private void refreshChart() {
        chart.setData(lastSweep, metricBox.getSelectedIndex(), (String) metricBox.getSelectedItem());
    }

    private void saveSweep() {
        if (lastSweepTable == null) return;
        JFileChooser fc = new JFileChooser(".");
        fc.setSelectedFile(new java.io.File("results/gui_sweep.csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            ResultTables.writeCsv(lastSweepTable, fc.getSelectedFile().toPath());
            sweepStatus.setText("Saved " + fc.getSelectedFile().getName());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage());
        }
    }

    private void savePlanCsv() {
        if (lastPlan == null) {
            JOptionPane.showMessageDialog(this, "Solve the model first.");
            return;
        }
        JFileChooser fc = new JFileChooser(".");
        fc.setDialogTitle("Choose a folder for the CSV files");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path dir = fc.getSelectedFile().toPath();
        try {
            ResultTables.writeCsv(ResultTables.shipments(lastPlan), dir.resolve("shipments.csv"));
            ResultTables.writeCsv(ResultTables.production(lastPlan), dir.resolve("production.csv"));
            ResultTables.writeCsv(ResultTables.modeByPeriod(lastPlan), dir.resolve("mode_by_period.csv"));
            JOptionPane.showMessageDialog(this, "Saved 3 CSV files in " + dir);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage());
        }
    }

    private static JComponent titled(String title, JTable table) {
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createTitledBorder(title));
        return sp;
    }

    private JTable makeTable(ResultTables.Table t) {
        DefaultTableModel model = new DefaultTableModel(t.rows(), t.columns()) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(22);
        DecimalFormat fmt = new DecimalFormat("#,##0.00");
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override protected void setValue(Object v) {
                if (v instanceof Double d) { setText(Double.isInfinite(d) ? "No limit" : fmt.format(d)); setHorizontalAlignment(RIGHT); }
                else { setText(v == null ? "" : v.toString()); setHorizontalAlignment(LEFT); }
            }
        });
        return table;
    }
    /** A small line chart drawn with Java2D, so no chart library is needed. */
    private static class ChartPanel extends JPanel {
        private List<ResultTables.SweepRow> rows = new ArrayList<>();
        private int metric;
        private String yLabel = "";
        String xLabel = "";

        ChartPanel() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(600, 300));
        }

        void setData(List<ResultTables.SweepRow> rows, int metric, String yLabel) {
            this.rows = rows;
            this.metric = metric;
            this.yLabel = yLabel;
            repaint();
        }

        private double y(ResultTables.SweepRow r) {
            return switch (metric) {
                case 0 -> r.profit();
                case 1 -> r.emissions();
                case 2 -> r.railPct();
                case 3 -> r.bought();
                default -> r.sold();
            };
        }

        private static String compact(double v) {
            double a = Math.abs(v);
            if (a >= 1e6) return String.format("%.2fM", v / 1e6);
            if (a >= 1e4) return String.format("%.0fk", v / 1e3);
            if (a >= 100) return String.format("%.0f", v);
            return String.format("%.1f", v);
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int left = 70, right = 30, top = 30, bottom = 55;
            g.setColor(Color.DARK_GRAY);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
            g.drawString(yLabel + " by scenario", left, 18);
            List<ResultTables.SweepRow> pts = new ArrayList<>();
            for (ResultTables.SweepRow r : rows) if (r.optimal()) pts.add(r);
            if (pts.isEmpty()) {
                g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
                g.drawString("Run a sweep to see the chart.", left, top + 40);
                return;
            }
            // x positions; a 'no limit' point is placed one step after the last finite value
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            int finiteCount = 0;
            for (ResultTables.SweepRow r : pts) if (!Double.isInfinite(r.value())) {
                minX = Math.min(minX, r.value());
                maxX = Math.max(maxX, r.value());
                finiteCount++;
            }
            if (finiteCount == 0) { minX = 0; maxX = 1; }
            double step = finiteCount > 1 ? (maxX - minX) / (finiteCount - 1) : 1;
            double infX = maxX + step;
            boolean hasInf = pts.stream().anyMatch(r -> Double.isInfinite(r.value()));
            double xHi = hasInf ? infX : maxX;
            if (xHi - minX < 1e-9) xHi = minX + 1;
            double yMin = Double.POSITIVE_INFINITY, yMax = Double.NEGATIVE_INFINITY;
            for (ResultTables.SweepRow r : pts) { yMin = Math.min(yMin, y(r)); yMax = Math.max(yMax, y(r)); }
            if (yMax - yMin < 1e-9) { yMin -= 1; yMax += 1; }
            double pad = (yMax - yMin) * 0.08;
            yMin -= pad;
            yMax += pad;
            if (metric >= 3 && yMin < 0) yMin = Math.min(0, yMin);
            int pw = w - left - right, ph = h - top - bottom;
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
            // grid and y ticks
            for (int k = 0; k <= 5; k++) {
                double val = yMin + (yMax - yMin) * k / 5.0;
                int py = top + ph - (int) (ph * k / 5.0);
                g.setColor(new Color(225, 225, 225));
                g.drawLine(left, py, left + pw, py);
                g.setColor(Color.DARK_GRAY);
                String s = compact(val);
                g.drawString(s, left - 6 - g.getFontMetrics().stringWidth(s), py + 4);
            }
            // x ticks
            for (ResultTables.SweepRow r : pts) {
                double xv = Double.isInfinite(r.value()) ? infX : r.value();
                int px = left + (int) (pw * (xv - minX) / (xHi - minX));
                g.setColor(Color.GRAY);
                g.drawLine(px, top + ph, px, top + ph + 4);
                String s = Double.isInfinite(r.value()) ? "no limit" : compact(r.value());
                g.setColor(Color.DARK_GRAY);
                g.drawString(s, px - g.getFontMetrics().stringWidth(s) / 2, top + ph + 18);
            }
            g.setColor(Color.GRAY);
            g.drawRect(left, top, pw, ph);
            g.setColor(Color.DARK_GRAY);
            g.drawString(xLabel, left + pw / 2 - g.getFontMetrics().stringWidth(xLabel) / 2, h - 10);
            // line and markers
            g.setColor(new Color(31, 119, 180));
            g.setStroke(new BasicStroke(2f));
            int lastX = -1, lastY = -1;
            for (ResultTables.SweepRow r : pts) {
                double xv = Double.isInfinite(r.value()) ? infX : r.value();
                int px = left + (int) (pw * (xv - minX) / (xHi - minX));
                int py = top + ph - (int) (ph * (y(r) - yMin) / (yMax - yMin));
                if (lastX >= 0) g.drawLine(lastX, lastY, px, py);
                lastX = px;
                lastY = py;
            }
            for (ResultTables.SweepRow r : pts) {
                double xv = Double.isInfinite(r.value()) ? infX : r.value();
                int px = left + (int) (pw * (xv - minX) / (xHi - minX));
                int py = top + ph - (int) (ph * (y(r) - yMin) / (yMax - yMin));
                g.fillOval(px - 4, py - 4, 8, 8);
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Gui().setVisible(true));
    }
}