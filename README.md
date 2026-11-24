Production-Distribution-Inventory Optimizer 
A linear programming model that plans production, inventory and distribution across multiple facilities and periods. 
The goal is to maximize profit under a carbon tax, an emission allowance and a capped carbon-credit market.
The Simplex solver is implemented from scratch in Java.

Multi-facility, multi-period, production-inventory-distribution planning
- The reference scale is 5 facilities x 10 customer regions x 4 products x 12 periods and 2 modes
Objective: Maximize profit
Carbon layer: Emission tax, free allowance and carbon credits (can be bought and sold) with a cap on purchases.
A scenario analysis tool that sweeps over the carbon tax, the allowance and the credit cap.
Java Swing GUI and Excel output of the results; shadow prices are reported by the solver.

Project Structure:
- lp/ Two-phase Simplex solver
- cbam/ App is the entry point, Gui is the swing interface, PlanModel is the model builder, Data and CsvLoader is the input
  DataValidator checks the input, Scenarios sweeps the data, Report and ResultTables are the outputs.
- fulldata/ includes the CSVs that were inputted.
- results/ The output of said CSVs.
- credit_cap_sweep_allow24000.csv + credit_cap_sweep_allow50000.csv are credit cap sweeps at fixed levels.
- results.xlsx + gui_excel.xlsx are the visualization of results on Excel.
- verify.py is an independent cross-check of the Java solver using SciPy.

-Firstly, FullDataCreator.java creates the data needed so that needs to be run first.
-Then, run the App.java file as it writes the shipments.csv, production.csv to results/
-After that, run the Gui.java file; it loads the input CSVs from the data folder, solves the model itself, and shows the results and sweeps
-There is also save as CSV option there. 
