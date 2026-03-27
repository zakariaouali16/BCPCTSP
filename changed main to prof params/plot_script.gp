# Set output format and style
set terminal pngcairo size 600,500 enhanced font 'Verdana,10'
set output 'algorithm_comparison.png'

# Define style
set style data histograms
set style histogram clustered gap 1
set style fill solid 1.0 border -1
set boxwidth 0.9

# Decoration
set ylabel "Prize Collected" font ",12"
set xlabel "Budget (miles)" font ",12"
set grid ytics
set key top left

# X-axis formatting
set xtics font ",10"
set ytics 0, 500, 3000
set yrange [0:3000]

# Plotting the three columns from results.dat
plot 'results.dat' using 2:xtic(1) title 'Greedy 1' linecolor rgb "white", \
     ''           using 3 title 'Greedy 2' linecolor rgb "#99ccba" fillstyle pattern 2, \
     ''           using 4 title 'BC-PC-TSP MARL' linecolor rgb "#c6e2ff" fillstyle pattern 1