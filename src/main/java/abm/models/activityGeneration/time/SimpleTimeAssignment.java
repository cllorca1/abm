package abm.models.activityGeneration.time;

import abm.data.plans.Activity;

import java.util.Random;

public class SimpleTimeAssignment implements TimeAssignment {

    static Random random = new Random(1);

    @Override
    public void assignTime(Activity activity) {

        double midnight = (activity.getDayOfWeek().getValue() - 1) * 24 * 3600;
        double startTime = Math.max(0, 10 * 3600 + random.nextGaussian() * 2 * 3600);
        double duration = Math.max(60, 1 * 3600 + random.nextGaussian() * 4 * 3600);

        //Todo add a method for scheduling

        activity.setStartTime_s(midnight + startTime);
        activity.setEndTime_s(midnight + startTime + duration);

    }
}
