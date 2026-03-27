set terminal pngcairo size 600,500 enhanced font 'Verdana,12'
set output 'distance_comparison.png'

# Histogram settings
set style data histograms
set style histogram clustered gap 1
set style fill border -1
set boxwidth 0.9

# Axis Labels
set ylabel "Distance (miles)"
set xlabel "Budget (miles)"
set grid ytics

# Y-Axis Range and Tics
set yrange [0:12000]
set ytics 0, 2000, 12000

# Legend position (top left inside)
set key top left Left reverse samplen 2

# Plotting with specific styles:
# 1: White, 2: Green Crosshatch, 3: Blue Pattern, 4: Solid Orange
plot 'distance.dat' using 2:xtic(1) title 'Greedy 1' lc rgb "white" fs solid, \
     ''             using 3 title 'Greedy 2' lc rgb "#99ccba" fs pattern 2, \
     ''             using 4 title 'P-MARL'   lc rgb "#c6e2ff" fs pattern 1, \
     ''             using 5 title 'Ant-Q'    lc rgb "#e69f00" fs solid