package pmarl;

public class CityNode {
	public String name;
	public double lat, lon;
	public int pop;
	public int originalIndex;
	static final double kmToMile = 0.62;

	CityNode(String name, double lat, double lon, int pop) // regular constructor
	{
		this.name = name;
		this.lat = lat;
		this.lon = lon;
		this.pop = pop;
	}

	CityNode(CityNode og) // deep copy constructor
	{
		this.name = og.name;
		this.lat = og.lat;
		this.lon = og.lon;
		this.pop = og.pop;
	}

	public static double getDistance(CityNode city1, CityNode city2) { 
		// city1.lat represents X, city1.lon represents Y
		double dx = city2.lat - city1.lat;
		double dy = city2.lon - city1.lon;

		// Standard Euclidean distance
		return Math.sqrt(dx * dx + dy * dy);
	}

	public static double deg2rad(double deg) {
		return (deg * Math.PI / 180);
	}
}
