# Set output format and file name
set terminal pngcairo size 600,400 enhanced font 'Times New Roman,12'
set output 'total_distance.png'

# Set titles and labels
set ylabel "Distance (miles)" font ",14"
set xlabel "Budget (miles)" font ",14"
set title "(b) Total Distance." offset 0,-1 font ",14"

# Configure the legend (key)
set key top left
set key spacing 1.2

# Configure the histogram style
set style data histograms
set style histogram cluster gap 1
set boxwidth 0.9

# Remove top and right borders for a cleaner look
set border 3
set xtics nomirror
set ytics nomirror

# Plot the data, applying the colors and fill styles directly
plot 'distance_data.txt' using 2:xtic(1) title 'Greedy 1' lc rgb "black" fs pattern 0 border rgb "black", \
     '' using 3 title 'Greedy 2' lc rgb "#408080" fs pattern 4 border rgb "black", \
     '' using 4 title 'P-MARL' lc rgb "#99CCFF" fs solid 0.8 border rgb "black", \
     '' using 5 title 'Ant-Q' lc rgb "#E69F00" fs solid 1.0 border rgb "black"