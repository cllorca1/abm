package abm.models.activityGeneration.splitByType;

import abm.data.DataSet;
import abm.data.plans.*;
import abm.data.pop.Person;
import abm.io.input.CoefficientsReader;
import abm.properties.AbitResources;
import abm.utils.AbitUtils;
import de.tum.bgu.msm.data.person.Gender;
import de.tum.bgu.msm.data.person.Occupation;
import org.apache.log4j.Logger;
import org.matsim.core.utils.collections.Tuple;

import java.nio.file.Path;
import java.util.*;

public class SplitByTypeModel implements SplitByType{

    private static final Logger logger = Logger.getLogger(SplitByTypeModel.class);

    private final DataSet dataSet;
    private Map<String, Double> splitOntoMandatoryCoefficients;
    private Map<DiscretionaryActivityType, Map<String, Double>> splitOntoDiscretionaryCoefficients;

    private boolean runCalibration = false;
    private Map<DiscretionaryActivityType, Double> updatedCalibrationFactors;


    public SplitByTypeModel(DataSet dataSet) {
        this.dataSet = dataSet;
        this.splitOntoMandatoryCoefficients = new CoefficientsReader(dataSet, "discAllActSplit", Path.of(AbitResources.instance.getString("act.split.type"))).readCoefficients();

        this.splitOntoDiscretionaryCoefficients = new HashMap<>();

        //the following loop will read the coefficient file for splitting discretionary acts onto discretionary tours
        Map<DiscretionaryActivityType, Map<String, Double>> splitOntoDiscretionaryCoefficients = new HashMap<>();
        for (DiscretionaryActivityType activityType: DiscretionaryActivityType.getDiscretionaryOntoDiscretionaryTypes()) {
            String columnName = activityType.toString().toLowerCase();
            Map<String, Double> coefficients = new CoefficientsReader(dataSet, columnName, Path.of(AbitResources.instance.getString("act.split.type.onto.discretionary"))).readCoefficients();
            splitOntoDiscretionaryCoefficients.put(activityType, coefficients);
        }

        this.splitOntoDiscretionaryCoefficients = splitOntoDiscretionaryCoefficients;

    }

    public SplitByTypeModel(DataSet dataSet, Boolean runCalibration) {
        this(dataSet);
        this.updatedCalibrationFactors = new HashMap<>();
        this.updatedCalibrationFactors.putIfAbsent(DiscretionaryActivityType.ON_MANDATORY_TOUR, 0.0);
        for (DiscretionaryActivityType activityType : DiscretionaryActivityType.getDiscretionaryOntoDiscretionaryTypes()) {
            this.updatedCalibrationFactors.putIfAbsent(activityType, 0.0);
        }
        this.runCalibration = runCalibration;
    }

    @Override
    public DiscretionaryActivityType assignActType(Activity activity, Person person) {

        long mandatoryTours = person.getPlan().getTours().values().stream().filter(t -> Purpose.getMandatoryPurposes().contains(t.getMainActivity().getPurpose())).count();

        double utilityOfBeingOnMandatoryTour;
        double probabilityOfBeingOnMandatoryTour;

        if (mandatoryTours == 0){
            probabilityOfBeingOnMandatoryTour = 0;
        } else {
            utilityOfBeingOnMandatoryTour = calculateUtilityOfBeingOnMandatoryTour(activity, person);
            probabilityOfBeingOnMandatoryTour = Math.exp(utilityOfBeingOnMandatoryTour) / (1 + Math.exp(utilityOfBeingOnMandatoryTour));
        }



        if (AbitUtils.getRandomObject().nextDouble() < probabilityOfBeingOnMandatoryTour && mandatoryTours > 0){
            activity.setDiscretionaryActivityType(DiscretionaryActivityType.ON_MANDATORY_TOUR);
            return DiscretionaryActivityType.ON_MANDATORY_TOUR;
        } else {
            activity.setDiscretionaryActivityType(DiscretionaryActivityType.ON_DISCRETIONARY_TOUR);
            return DiscretionaryActivityType.ON_DISCRETIONARY_TOUR;

        }



    }


    public DiscretionaryActivityType assignActTypeForDiscretionaryTourActs(Activity activity, Person person, int numActsNotOnMandatoryTours) {
        double utilityOfBeingOnDiscretionaryTour;
        double probabilityOfBeingOnDiscretionaryTour;
        switch (activity.getPurpose()) {
            case HOME:
            case WORK:
            case EDUCATION:
                throw new RuntimeException("This was intended only for discretionary activities");
            case ACCOMPANY:

                double accompanyTours = person.getPlan().getTours().values().stream()
                        .flatMap(tour -> tour.getActivities().values().stream())
                    .filter(act -> act.getPurpose().equals(Purpose.ACCOMPANY) && act.getDiscretionaryActivityType().equals(DiscretionaryActivityType.ACCOMPANY_PRIMARY)).count();

                if (accompanyTours == 0) {
                    activity.setDiscretionaryActivityType(DiscretionaryActivityType.ACCOMPANY_PRIMARY);
                    return DiscretionaryActivityType.ACCOMPANY_PRIMARY;
                }
                utilityOfBeingOnDiscretionaryTour = calculateUtilityOfBeingOnDiscretionaryTour(DiscretionaryActivityType.ACCOMPANY_ON_ACCOMPANY, person, numActsNotOnMandatoryTours);
                probabilityOfBeingOnDiscretionaryTour = Math.exp(utilityOfBeingOnDiscretionaryTour) / (1 + Math.exp(utilityOfBeingOnDiscretionaryTour));
                if (AbitUtils.getRandomObject().nextDouble() < probabilityOfBeingOnDiscretionaryTour) {
                    activity.setDiscretionaryActivityType(DiscretionaryActivityType.ACCOMPANY_ON_ACCOMPANY);
                    return DiscretionaryActivityType.ACCOMPANY_ON_ACCOMPANY;
                } else {
                    activity.setDiscretionaryActivityType(DiscretionaryActivityType.ACCOMPANY_PRIMARY);
                    return DiscretionaryActivityType.ACCOMPANY_PRIMARY;
                }

            case SHOPPING:
                utilityOfBeingOnDiscretionaryTour = calculateUtilityOfBeingOnDiscretionaryTour(DiscretionaryActivityType.SHOP_ON_ACCOMPANY, person, numActsNotOnMandatoryTours);
                probabilityOfBeingOnDiscretionaryTour = Math.exp(utilityOfBeingOnDiscretionaryTour) / (1 + Math.exp(utilityOfBeingOnDiscretionaryTour));

                if (person.getPlan().getTours().values().stream()
                    .flatMap(tour -> tour.getActivities().values().stream())
                    .filter(act -> act.getPurpose().equals(Purpose.ACCOMPANY) && act.getDiscretionaryActivityType().equals(DiscretionaryActivityType.ACCOMPANY_PRIMARY)).count()
                    > 0) {
                    if (AbitUtils.getRandomObject().nextDouble() < probabilityOfBeingOnDiscretionaryTour) {
                        activity.setDiscretionaryActivityType(DiscretionaryActivityType.SHOP_ON_ACCOMPANY);
                        return DiscretionaryActivityType.SHOP_ON_ACCOMPANY;
                    }
                }

                if (person.getPlan().getTours().values().stream()
                                .flatMap(tour-> tour.getActivities().values().stream())
                        .filter(act -> act.getPurpose().equals(Purpose.SHOPPING) &&
                                act.getDiscretionaryActivityType().equals(DiscretionaryActivityType.SHOP_PRIMARY)).count() == 0) {
                    activity.setDiscretionaryActivityType(DiscretionaryActivityType.SHOP_PRIMARY);
                    return DiscretionaryActivityType.SHOP_PRIMARY;
                }

                utilityOfBeingOnDiscretionaryTour = calculateUtilityOfBeingOnDiscretionaryTour(DiscretionaryActivityType.SHOP_ON_SHOP, person, numActsNotOnMandatoryTours);
                probabilityOfBeingOnDiscretionaryTour = Math.exp(utilityOfBeingOnDiscretionaryTour) / (1 + Math.exp(utilityOfBeingOnDiscretionaryTour));

                if (AbitUtils.getRandomObject().nextDouble() < probabilityOfBeingOnDiscretionaryTour) {
                    activity.setDiscretionaryActivityType(DiscretionaryActivityType.SHOP_ON_SHOP);
                    return DiscretionaryActivityType.SHOP_ON_SHOP;
                } else {
                    activity.setDiscretionaryActivityType(DiscretionaryActivityType.SHOP_PRIMARY);
                    return DiscretionaryActivityType.SHOP_PRIMARY;
                }
            case OTHER:
                utilityOfBeingOnDiscretionaryTour = calculateUtilityOfBeingOnDiscretionaryTour(DiscretionaryActivityType.OTHER_ON_ACCOMPANY, person, numActsNotOnMandatoryTours);
                probabilityOfBeingOnDiscretionaryTour = Math.exp(utilityOfBeingOnDiscretionaryTour) / (1 + Math.exp(utilityOfBeingOnDiscretionaryTour));
                if (person.getPlan().getTours().values().stream()
                        .flatMap(tour -> tour.getActivities().values().stream())
                        .filter(act -> act.getPurpose().equals(Purpose.ACCOMPANY) && act.getDiscretionaryActivityType().equals(DiscretionaryActivityType.ACCOMPANY_PRIMARY)).count()
                        > 0) {
                    if (AbitUtils.getRandomObject().nextDouble() < probabilityOfBeingOnDiscretionaryTour) {
                        activity.setDiscretionaryActivityType(DiscretionaryActivityType.OTHER_ON_ACCOMPANY);
                        return DiscretionaryActivityType.OTHER_ON_ACCOMPANY;
                    }
                }

                utilityOfBeingOnDiscretionaryTour = calculateUtilityOfBeingOnDiscretionaryTour(DiscretionaryActivityType.OTHER_ON_SHOP, person, numActsNotOnMandatoryTours);
                probabilityOfBeingOnDiscretionaryTour = Math.exp(utilityOfBeingOnDiscretionaryTour) / (1 + Math.exp(utilityOfBeingOnDiscretionaryTour));

                if (person.getPlan().getTours().values().stream()
                        .flatMap(tour -> tour.getActivities().values().stream())
                        .filter(act -> act.getPurpose().equals(Purpose.SHOPPING) &&
                                act.getDiscretionaryActivityType().equals(DiscretionaryActivityType.SHOP_PRIMARY)).count()
                        > 0) {
                    if (AbitUtils.getRandomObject().nextDouble() < probabilityOfBeingOnDiscretionaryTour) {
                        activity.setDiscretionaryActivityType(DiscretionaryActivityType.OTHER_ON_SHOP);
                        return DiscretionaryActivityType.OTHER_ON_SHOP;
                    }
                }

                if (person.getPlan().getTours().values().stream()
                        .flatMap(tour -> tour.getActivities().values().stream())
                        .filter(act -> act.getPurpose().equals(Purpose.OTHER) &&
                                act.getDiscretionaryActivityType().equals(DiscretionaryActivityType.OTHER_PRIMARY)).count()
                        == 0) {
                    activity.setDiscretionaryActivityType(DiscretionaryActivityType.OTHER_PRIMARY);
                    return DiscretionaryActivityType.OTHER_PRIMARY;
                }
                utilityOfBeingOnDiscretionaryTour = calculateUtilityOfBeingOnDiscretionaryTour(DiscretionaryActivityType.OTHER_ON_OTHER, person, numActsNotOnMandatoryTours);
                probabilityOfBeingOnDiscretionaryTour = Math.exp(utilityOfBeingOnDiscretionaryTour) / (1 + Math.exp(utilityOfBeingOnDiscretionaryTour));
                if (AbitUtils.getRandomObject().nextDouble() < probabilityOfBeingOnDiscretionaryTour) {
                    activity.setDiscretionaryActivityType(DiscretionaryActivityType.OTHER_ON_OTHER);
                    return DiscretionaryActivityType.OTHER_ON_OTHER;
                }
                activity.setDiscretionaryActivityType(DiscretionaryActivityType.OTHER_PRIMARY);
                return DiscretionaryActivityType.OTHER_PRIMARY;
            case RECREATION:
                utilityOfBeingOnDiscretionaryTour = calculateUtilityOfBeingOnDiscretionaryTour(DiscretionaryActivityType.RECREATION_ON_ACCOMPANY, person, numActsNotOnMandatoryTours);
                probabilityOfBeingOnDiscretionaryTour = Math.exp(utilityOfBeingOnDiscretionaryTour) / (1 + Math.exp(utilityOfBeingOnDiscretionaryTour));

                if (person.getPlan().getTours().values().stream()
                        .flatMap(tour -> tour.getActivities().values().stream())
                        .filter(act -> act.getPurpose().equals(Purpose.ACCOMPANY) && act.getDiscretionaryActivityType().equals(DiscretionaryActivityType.ACCOMPANY_PRIMARY)).count()
                        > 0) {
                    if (AbitUtils.getRandomObject().nextDouble() < probabilityOfBeingOnDiscretionaryTour) {
                        activity.setDiscretionaryActivityType(DiscretionaryActivityType.RECREATION_ON_ACCOMPANY);
                        return DiscretionaryActivityType.RECREATION_ON_ACCOMPANY;
                    }
                }

                utilityOfBeingOnDiscretionaryTour = calculateUtilityOfBeingOnDiscretionaryTour(DiscretionaryActivityType.RECREATION_ON_SHOP, person, numActsNotOnMandatoryTours);
                probabilityOfBeingOnDiscretionaryTour = Math.exp(utilityOfBeingOnDiscretionaryTour) / (1 + Math.exp(utilityOfBeingOnDiscretionaryTour));
                if (person.getPlan().getTours().values().stream()
                        .flatMap(tour -> tour.getActivities().values().stream())
                        .filter(act -> act.getPurpose().equals(Purpose.SHOPPING) &&
                                act.getDiscretionaryActivityType().equals(DiscretionaryActivityType.SHOP_PRIMARY)).count()
                        > 0) {
                    if (AbitUtils.getRandomObject().nextDouble() < probabilityOfBeingOnDiscretionaryTour) {
                        activity.setDiscretionaryActivityType(DiscretionaryActivityType.RECREATION_ON_SHOP);
                        return DiscretionaryActivityType.RECREATION_ON_SHOP;
                    }
                }


                utilityOfBeingOnDiscretionaryTour = calculateUtilityOfBeingOnDiscretionaryTour(DiscretionaryActivityType.RECREATION_ON_OTHER, person, numActsNotOnMandatoryTours);
                probabilityOfBeingOnDiscretionaryTour = Math.exp(utilityOfBeingOnDiscretionaryTour) / (1 + Math.exp(utilityOfBeingOnDiscretionaryTour));
                if (person.getPlan().getTours().values().stream()
                        .flatMap(tour -> tour.getActivities().values().stream())
                        .filter(act -> act.getPurpose().equals(Purpose.OTHER) &&
                                act.getDiscretionaryActivityType().equals(DiscretionaryActivityType.OTHER_PRIMARY)).count()
                        > 0) {
                    if (AbitUtils.getRandomObject().nextDouble() < probabilityOfBeingOnDiscretionaryTour) {
                        activity.setDiscretionaryActivityType(DiscretionaryActivityType.RECREATION_ON_OTHER);
                        return DiscretionaryActivityType.RECREATION_ON_OTHER;
                    }
                }

                if (person.getPlan().getTours().values().stream()
                        .flatMap(tour -> tour.getActivities().values().stream())
                        .filter(act -> act.getPurpose().equals(Purpose.RECREATION) &&
                                act.getDiscretionaryActivityType().equals(DiscretionaryActivityType.RECREATION_PRIMARY)).count()
                        == 0) {
                    activity.setDiscretionaryActivityType(DiscretionaryActivityType.RECREATION_PRIMARY);
                    return DiscretionaryActivityType.RECREATION_PRIMARY;
                }
                utilityOfBeingOnDiscretionaryTour = calculateUtilityOfBeingOnDiscretionaryTour(DiscretionaryActivityType.RECREATION_ON_RECREATION, person, numActsNotOnMandatoryTours);
                probabilityOfBeingOnDiscretionaryTour = Math.exp(utilityOfBeingOnDiscretionaryTour) / (1 + Math.exp(utilityOfBeingOnDiscretionaryTour));
                if (AbitUtils.getRandomObject().nextDouble() < probabilityOfBeingOnDiscretionaryTour) {
                    activity.setDiscretionaryActivityType(DiscretionaryActivityType.RECREATION_ON_RECREATION);
                    return DiscretionaryActivityType.RECREATION_ON_RECREATION;
                }
                activity.setDiscretionaryActivityType(DiscretionaryActivityType.RECREATION_PRIMARY);
                return DiscretionaryActivityType.RECREATION_PRIMARY;
        }



        return null; //forced to have return statement or intellij throws an error
    }


    public double calculateUtilityOfBeingOnMandatoryTour(Activity activity, Person person){


        double utility = 0.;

        utility += splitOntoMandatoryCoefficients.get("(Intercept)");

        //missing area type variables here (not significant!)

        switch (person.getHousehold().getEconomicStatus()){
            case from0to800:
                utility += splitOntoMandatoryCoefficients.get("hh.econStatus_1");
                break;
            case from801to1600:
                utility += splitOntoMandatoryCoefficients.get("hh.econStatus_2");
                break;
            case from1601to2400:
                utility += splitOntoMandatoryCoefficients.get("hh.econStatus_3");
                break;
            case from2401:
                //utility += splitOntoMandatoryCoefficients.get("hh.econStatus_4");
                break;
        }

        if (person.getHousehold().getPersons().stream().filter(p -> p.getOccupation().equals(Occupation.EMPLOYED)).count() == 0){
            utility += splitOntoMandatoryCoefficients.get("hh.notEmployed");
        }

        int householdSize = person.getHousehold().getPersons().size();
        if (householdSize == 2) {
            utility += splitOntoMandatoryCoefficients.get("hh.size_2");
        } else if (householdSize == 3) {
            utility += splitOntoMandatoryCoefficients.get("hh.size_3");
        } else if (householdSize == 4) {
            utility += splitOntoMandatoryCoefficients.get("hh.size_4");
        } else if ((householdSize >= 5) ) {
            utility += splitOntoMandatoryCoefficients.get("hh.size_5");
        }

        // Number of children in household
        int householdChildren = (int) person.getHousehold().getPersons().stream().filter(p -> p.getAge() < 18).count();
        if (householdChildren == 1) {
            utility += splitOntoMandatoryCoefficients.get("hh.children_1");
        } else if (householdChildren == 2) {
            utility += splitOntoMandatoryCoefficients.get("hh.children_2");
        } else if (householdChildren >= 3) {
            utility += splitOntoMandatoryCoefficients.get("hh.children_3");
        }

        int householdAdult = householdSize - householdChildren;
        if (householdAdult == 1) {
            utility += splitOntoMandatoryCoefficients.get("hh.adults_1");
        } else if (householdAdult == 2) {
            utility += splitOntoMandatoryCoefficients.get("hh.adults_2");
        } else if (householdAdult >= 3) {
            utility += splitOntoMandatoryCoefficients.get("hh.adults_3");
        } else if (householdAdult >= 4) {
            utility += splitOntoMandatoryCoefficients.get("hh.adults_4");
        }

        if (householdChildren != 0) {
            double adultsPerChild = householdAdult / householdChildren;
            if (adultsPerChild < 1) {
                utility += splitOntoMandatoryCoefficients.get("hh.adults_per_child_0");
            } else if (adultsPerChild == 1) {
                utility += splitOntoMandatoryCoefficients.get("hh.adults_per_child_1");
            } else {
                utility += splitOntoMandatoryCoefficients.get("hh.adults_per_child_2");
            }
        }


        int age = person.getAge();

        if (age < 15){

        } else if (age < 25) {
            utility += splitOntoMandatoryCoefficients.get("p.age_gr_1");
        } else if (age < 35) {
            utility += splitOntoMandatoryCoefficients.get("p.age_gr_2");
        } else if (age < 45) {
            utility += splitOntoMandatoryCoefficients.get("p.age_gr_3");
        } else if (age < 55) {
            utility += splitOntoMandatoryCoefficients.get("p.age_gr_4");
        } else if (age < 65) {
            utility += splitOntoMandatoryCoefficients.get("p.age_gr_5");
        } else {
            utility += splitOntoMandatoryCoefficients.get("p.age_gr_6");
        }

        switch (person.getOccupation()){


            case STUDENT:
                utility += splitOntoMandatoryCoefficients.get("p.occupationStatus_Student");
                break;
            case EMPLOYED:
                //todo move this into the person reader and then define a partTime variable status?
                final Tour workTour = person.getPlan().getTours().values().stream().
                        filter(t -> t.getMainActivity().getPurpose().equals(Purpose.WORK)).findAny().orElse(null);
                if (workTour != null){
                    if (workTour.getMainActivity().getDuration() > 6 * 60) {
                        utility += splitOntoMandatoryCoefficients.get("p.occupationStatus_Employed");
                    } else {
                        utility += splitOntoMandatoryCoefficients.get("p.occupationStatus_Halftime");
                    }
                }
                break;
            case UNEMPLOYED:
                utility += splitOntoMandatoryCoefficients.get("p.occupationStatus_Unemployed");
                break;
            case RETIREE:
                //todo is this like unemployed?
                utility += splitOntoMandatoryCoefficients.get("p.occupationStatus_Unemployed");
                break;
            case TODDLER:
                //todo is this like unemployed?
                utility += splitOntoMandatoryCoefficients.get("p.occupationStatus_Unemployed");
                break;
        }

        if (person.getGender().equals(Gender.FEMALE)){
            utility += splitOntoMandatoryCoefficients.get("p.female");
        }

        if (person.isHasLicense()){
            utility += splitOntoMandatoryCoefficients.get("p.driversLicense");
        }

        if (person.hasBicycle()){
            utility += splitOntoMandatoryCoefficients.get("p.ownBicycle");
        }

        int cars = person.getHousehold().getNumberOfCars();

        if (cars == 1){
            utility += splitOntoMandatoryCoefficients.get("hh.cars_1");
        } else if (cars == 2){
            utility += splitOntoMandatoryCoefficients.get("hh.cars_2");
        } else if (cars >= 2){
            utility += splitOntoMandatoryCoefficients.get("hh.cars_3");
        }

        switch (person.getHabitualMode()){
            //todo this should be the time on the habitual mode instead, not a dummy variable!
            case PT:
                utility += splitOntoMandatoryCoefficients.get("p.t_mand_habmode_PT");
                break;
            case CAR_DRIVER:
                utility += splitOntoMandatoryCoefficients.get("p.t_mand_habmode_car");
                break;
            case BIKE:
                utility += splitOntoMandatoryCoefficients.get("p.t_mand_habmode_cycle");
                break;
            case WALK:
                utility += splitOntoMandatoryCoefficients.get("p.t_mand_habmode_walk");
                break;
            case UNKNOWN:
                utility += splitOntoMandatoryCoefficients.get("p.t_mand_habmode_car");

        }

        switch (activity.getPurpose()){

            case HOME:
            case WORK:
            case EDUCATION:
                throw new RuntimeException("Trying to define the type of an home or mandatory activity - this model is" +
                        " only intended for discretionary purposes");
            case ACCOMPANY:
                utility += splitOntoMandatoryCoefficients.get("act.purpose_accompany");
                break;
            case SHOPPING:
                utility += splitOntoMandatoryCoefficients.get("act.purpose_shop");
                break;
            case OTHER:
                utility += splitOntoMandatoryCoefficients.get("act.purpose_other");
                break;
            case RECREATION:
                utility += splitOntoMandatoryCoefficients.get("act.purpose_recreation");
                break;
        }


        if (runCalibration) {
            utility = utility + updatedCalibrationFactors.get(DiscretionaryActivityType.ON_MANDATORY_TOUR);
        }


        return utility;

    }

    public double calculateUtilityOfBeingOnDiscretionaryTour(DiscretionaryActivityType discretionaryActivityType, Person person, int numActsNotOnMandatoryTours){

        double utility = 0.;

        switch (discretionaryActivityType){

            case ACCOMPANY_ON_ACCOMPANY:
                utility += splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("(Intercept)");
                utility += numActsNotOnMandatoryTours*splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("p.acts_accompany_disc");
                break;
            case SHOP_ON_ACCOMPANY:
                utility += splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("(Intercept)");
                utility += person.getPlan().getTours().values().stream().
                        filter(t -> t.getMainActivity().getPurpose().equals(Purpose.ACCOMPANY)).count()
                        * splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("p.acts_accompany_main");
                utility += numActsNotOnMandatoryTours * splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("p.acts_shop_disc");
                break;
            case SHOP_ON_SHOP:
                utility += splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("(Intercept)");
                utility += numActsNotOnMandatoryTours * splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("p.acts_shop_disc");
                break;
            case OTHER_ON_ACCOMPANY:
                utility += splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("(Intercept)");
                utility += person.getPlan().getTours().values().stream().
                        filter(t -> t.getMainActivity().getPurpose().equals(Purpose.ACCOMPANY)).count()
                        * splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("p.acts_accompany_main");
                utility += numActsNotOnMandatoryTours * splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("p.acts_other_disc");
                break;
            case OTHER_ON_SHOP:
                utility += splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("(Intercept)");
                utility += person.getPlan().getTours().values().stream().
                        filter(t -> t.getMainActivity().getPurpose().equals(Purpose.SHOPPING)).count()
                        * splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("p.acts_shop_main");
                break;
            case OTHER_ON_OTHER:
                utility += splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("(Intercept)");
                utility += numActsNotOnMandatoryTours * splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("p.acts_other_disc");
                break;
            case RECREATION_ON_ACCOMPANY:
                utility += splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("(Intercept)");
                utility += person.getPlan().getTours().values().stream().
                        filter(t -> t.getMainActivity().getPurpose().equals(Purpose.ACCOMPANY)).count()
                        * splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("p.acts_accompany_main");
                utility += numActsNotOnMandatoryTours * splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("p.acts_recreation_disc");
                break;
            case RECREATION_ON_SHOP:
                utility += splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("(Intercept)");
                utility += person.getPlan().getTours().values().stream().
                        filter(t -> t.getMainActivity().getPurpose().equals(Purpose.SHOPPING)).count()
                        * splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("p.acts_shop_main");
                break;
            case RECREATION_ON_OTHER:
                utility += splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("(Intercept)");
                utility += person.getPlan().getTours().values().stream().
                        filter(t -> t.getMainActivity().getPurpose().equals(Purpose.OTHER)).count()
                        * splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("p.acts_other_main");
                break;
            case RECREATION_ON_RECREATION:
                utility += splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("(Intercept)");
                utility += numActsNotOnMandatoryTours * splitOntoDiscretionaryCoefficients.get(discretionaryActivityType).get("p.acts_recreation_disc");
                break;
        }

        if (runCalibration) {
            utility = utility + updatedCalibrationFactors.get(discretionaryActivityType);
        }
        return utility;

    }

    public void updateCalibrationFactor(Map<DiscretionaryActivityType, Double> newCalibrationFactors) {

        double updatedCalibrationFactor;
        double calibrationFactorFromLastIteration;

        calibrationFactorFromLastIteration = this.updatedCalibrationFactors.get(DiscretionaryActivityType.ON_MANDATORY_TOUR);
        updatedCalibrationFactor = newCalibrationFactors.get(DiscretionaryActivityType.ON_MANDATORY_TOUR);
        this.updatedCalibrationFactors.replace(DiscretionaryActivityType.ON_MANDATORY_TOUR, updatedCalibrationFactor + calibrationFactorFromLastIteration);
        logger.info("Calibration factor for ON_MANDATORY_TOUR" + "\t" + ": " + updatedCalibrationFactor);

        for (DiscretionaryActivityType activityType : DiscretionaryActivityType.getDiscretionaryOntoDiscretionaryTypes()) {
            calibrationFactorFromLastIteration = this.updatedCalibrationFactors.get(activityType);
            updatedCalibrationFactor = newCalibrationFactors.get(activityType);
            this.updatedCalibrationFactors.replace(activityType, updatedCalibrationFactor + calibrationFactorFromLastIteration);
            logger.info("Calibration factor for " + activityType + "\t" + ": " + updatedCalibrationFactor);
        }
    }

    // for model that splits discretionary acts to be on mandatory tours or NOT on mandatory tours
    public Map<String, Double>  obtainSplitOntoMandatoryCoefficientsTable() {

        double originalCalibrationFactor;
        double updatedCalibrationFactor;
        double latestCalibrationFactor;


        originalCalibrationFactor = this.splitOntoMandatoryCoefficients.get("calibration");
        updatedCalibrationFactor = updatedCalibrationFactors.get(DiscretionaryActivityType.ON_MANDATORY_TOUR);
        latestCalibrationFactor = originalCalibrationFactor + updatedCalibrationFactor;
        splitOntoMandatoryCoefficients.replace("calibration", latestCalibrationFactor);


        return this.splitOntoMandatoryCoefficients;
    }

    //for models splitting discretionary acts onto discretionary tours
    public Map<DiscretionaryActivityType, Map<String, Double>> obtainSplitOntoDiscretionaryCoefficientsTable() {

        double originalCalibrationFactor;
        double updatedCalibrationFactor;
        double latestCalibrationFactor;


        for (DiscretionaryActivityType activityType : DiscretionaryActivityType.getDiscretionaryOntoDiscretionaryTypes()) {
            originalCalibrationFactor = this.splitOntoDiscretionaryCoefficients.get(activityType).get("calibration");
            updatedCalibrationFactor = updatedCalibrationFactors.get(activityType);
            latestCalibrationFactor = originalCalibrationFactor + updatedCalibrationFactor;
            splitOntoDiscretionaryCoefficients.get(activityType).replace("calibration", latestCalibrationFactor);
        }

        return this.splitOntoDiscretionaryCoefficients;
    }

}
