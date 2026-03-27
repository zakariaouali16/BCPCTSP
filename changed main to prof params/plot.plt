# Set terminal to output a PNG image with a specific size and font
set terminal pngcairo size 500,450 enhanced font 'Arial,14'
set output 'prize_collected.png'

# Set labels and title
set xlabel "Budget (miles)" font ",16"
set ylabel "Prize Collected" font ",16"
set title "(a) Total Prize." offset 0,-1.5 font ",16"

# Adjust axes and borders to match the image
set yrange [0:800]  # Adjusted to fit your $498 data point; change to 3000 if needed
set ytics 200
set border 15 lw 1.2
set key top left Left reverse

# Configure the grouped histogram with error bars
set style data histograms
set style histogram errorbars gap 1.5 lw 1.2
set boxwidth 0.8 relative

# Plot the data using specific column combinations and fill styles
# using MeanColumn:StdDevColumn:xtic(XAxisLabelColumn)
plot 'data.txt' using 2:3:xtic(1) title 'Greedy 1' lc rgb "black" fs empty, \
     '' using 4:5 title 'Greedy 2' lc rgb "#4fa284" fs pattern 4 transparent, \
     '' using 6:7 title 'P-MARL' lc rgb "#8da0cb" fs solid 0.5 border rgb "#3182bd", \
     '' using 8:9 title 'Ant-Q' lc rgb "#e69f00" fs solid 1.0 border rgb "black"