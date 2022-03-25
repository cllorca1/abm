package abm.data.plans;


import abm.Utils;
import abm.data.geo.Location;
import abm.data.geo.MicroscopicLocation;
import abm.data.pop.Person;

import java.time.DayOfWeek;

public class Activity implements Comparable<Activity> {

    private Person person;
    private Tour tour;
    private Purpose purpose;
    private DayOfWeek dayOfWeek;
    private double startTime_s;
    private double endTime_s;
    private DiscretionaryActivityType discretionaryActivityType;

    private Location location;


    public Activity(Purpose purpose, double startTime_s, double endTime_s, Location location) {
        this.purpose = purpose;
        this.startTime_s = startTime_s;
        this.endTime_s = endTime_s;
        this.location = location;
    }

    public Activity(Person person, Purpose purpose) {
        this.purpose = purpose;
        this.person = person;
    }

    public Purpose getPurpose() {
        return purpose;
    }

    public double getStartTime_s() {
        return startTime_s;
    }

    public double getEndTime_s() {
        return endTime_s;
    }

    public Location getLocation() {
        return location;
    }

    public void setStartTime_s(double startTime_s) {
        this.startTime_s = startTime_s;
    }

    public void setEndTime_s(double endTime_s) {
        this.endTime_s = endTime_s;
    }

    public double getDuration() {
        return endTime_s - startTime_s;
    }

    public void setLocation(Location location) {
        this.location = location;
    }

    @Override
    public int compareTo(Activity activity) {
        if (activity.getStartTime_s() < this.getStartTime_s()) {
            return 1;
        } else if (activity.getStartTime_s() == this.getStartTime_s()) {
            return 0;
        } else {
            return -1;
        }
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();

        builder.append(person.getHousehold().getId()).append(Utils.SEPARATOR);
        builder.append(person.getId()).append(Utils.SEPARATOR);
        builder.append(tour.getActivities().firstKey()/60).append(Utils.SEPARATOR);
        builder.append(dayOfWeek.getValue()).append(Utils.SEPARATOR);
        builder.append(startTime_s / 60).append(Utils.SEPARATOR);
        builder.append(endTime_s / 60).append(Utils.SEPARATOR);
        builder.append(purpose).append(Utils.SEPARATOR);
        builder.append(location.getZoneId()).append(Utils.SEPARATOR);

        if (location instanceof MicroscopicLocation) {
            final MicroscopicLocation microscopicLocation = (MicroscopicLocation) (MicroscopicLocation) location;
            builder.append(microscopicLocation.getX()).append(Utils.SEPARATOR);
            builder.append(microscopicLocation.getY()).append(Utils.SEPARATOR);
        } else {
            builder.append(-1).append(Utils.SEPARATOR);
            builder.append(-1).append(Utils.SEPARATOR);
        }

        if (discretionaryActivityType != null){
            builder.append(discretionaryActivityType);
        } else {
            builder.append("MANDATORY");
        }


        return builder.toString();
    }


    public static String getHeader() {
        StringBuilder builder = new StringBuilder();
        builder.append("person_id").append(Utils.SEPARATOR);
        builder.append("household_id").append(Utils.SEPARATOR);
        builder.append("tour_start_time_min").append(Utils.SEPARATOR);
        builder.append("day").append(Utils.SEPARATOR);
        builder.append("start_time_min").append(Utils.SEPARATOR);
        builder.append("end_time_min").append(Utils.SEPARATOR);
        builder.append("purpose").append(Utils.SEPARATOR);
        builder.append("zone_id").append(Utils.SEPARATOR);
        builder.append("x").append(Utils.SEPARATOR);
        builder.append("y").append(Utils.SEPARATOR);
        builder.append("activity_type");
        return builder.toString();
    }

    public DayOfWeek getDayOfWeek() {
        return dayOfWeek;
    }

    public void setDayOfWeek(DayOfWeek dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    public DiscretionaryActivityType getDiscretionaryActivityType() {
        return discretionaryActivityType;
    }

    public void setDiscretionaryActivityType(DiscretionaryActivityType discretionaryActivityType) {
        this.discretionaryActivityType = discretionaryActivityType;
    }

    public void setTour(Tour tour) {
        this.tour = tour;
    }
}
