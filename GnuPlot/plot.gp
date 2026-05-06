# Output settings
set terminal pngcairo size 900,450 enhanced font 'Arial,11'
set output 'results_plot.png'

# Histogram and Error Bar styling
set style data histograms
set style histogram errorbars gap 1 lw 1.5
set style fill solid 0.7 border -1
set boxwidth 0.7 relative
set errorbars linecolor black
set grid ytics

# Enable side-by-side subplots
set multiplot layout 1,2 title "Algorithm Performance (Average & 95% CI)" font ",14"

# --- Subplot 1: Average Prize ---
set title "Average Prize"
set ylabel "Prize Score"
set yrange [0:*]
# Skip the first row (header) and plot Prize_Avg (col 2) with Prize_CI (col 3)
plot 'plot_data.dat' every ::1 using 2:3:xtic(1) title 'Prize' linecolor rgb "#87CEEB"

# --- Subplot 2: Average Distance ---
set title "Average Distance"
set ylabel "Distance"
# Setting lower bounds so the bars don't drown out the CI differences
set yrange [9500:10100] 
# Plot Distance_Avg (col 4) with Distance_CI (col 5)
plot 'plot_data.dat' every ::1 using 4:5:xtic(1) title 'Distance' linecolor rgb "#90EE90"

unset multiplot