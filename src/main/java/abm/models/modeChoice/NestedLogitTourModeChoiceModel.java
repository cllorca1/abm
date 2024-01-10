package abm.models.modeChoice;

import abm.data.DataSet;
import abm.data.geo.RegioStaR2;
import abm.data.geo.RegioStaR7;
import abm.data.plans.*;
import abm.data.pop.Household;
import abm.data.pop.Person;
import abm.data.vehicle.Car;
import abm.data.vehicle.Vehicle;
import abm.io.input.CoefficientsReader;
import abm.properties.AbitResources;
import abm.utils.AbitUtils;
import abm.utils.LogitTools;
import de.tum.bgu.msm.data.person.Disability;
import de.tum.bgu.msm.data.person.Gender;
import de.tum.bgu.msm.util.MitoUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.utils.collections.Tuple;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.*;
import java.util.stream.Collectors;


public class NestedLogitTourModeChoiceModel implements TourModeChoice {

    private final static Logger logger = LogManager.getLogger(NestedLogitTourModeChoiceModel.class);
    private final DataSet dataSet;
    private boolean runCalibration = false;
    private Map<Purpose, Map<Mode, Map<String, Double>>> purposeModeCoefficients;

    private static final LogitTools<abm.data.plans.Mode> logitTools = new LogitTools<>(abm.data.plans.Mode.class);
    private Map<Purpose, List<Tuple<EnumSet<abm.data.plans.Mode>, Double>>> nests = null;

    Map<Purpose, Map<DayOfWeek, Map<Mode, Double>>> updatedCalibrationFactors = new HashMap<>();

    private final static double fuelCostEurosPerKm = 0.065;
    private final static double transitFareEurosPerKm = 0.12;

    private static final double SPEED_WALK_KMH = 4.;
    private static final double SPEED_BICYCLE_KMH = 10.;

    public NestedLogitTourModeChoiceModel(DataSet dataSet) {
        this.dataSet = dataSet;
        this.purposeModeCoefficients = new HashMap<>();

        //the following loop will read the coefficient file Mode.getModes().size() times, which is acceptable?
        Map<Purpose, Map<Mode, Map<String, Double>>> purposeModeCoefficients = new HashMap<>();
        Map<Purpose, List<Tuple<EnumSet<abm.data.plans.Mode>, Double>>> nests = new HashMap<>();
        for (Purpose purpose : Purpose.getAllPurposes()) {
            Map<Mode, Map<String, Double>> modeCoefficients = new HashMap<>();
            List<Tuple<EnumSet<abm.data.plans.Mode>, Double>> nestsByPurpose = new ArrayList<>();
            Path pathToFilePurpose;
            if (purpose == Purpose.EDUCATION || purpose == Purpose.WORK) {
                pathToFilePurpose = Path.of(AbitResources.instance.getString("tour.mode.coef") + "mandatoryMode_nestedLogit.csv");
            } else {
                pathToFilePurpose = Path.of(AbitResources.instance.getString("tour.mode.coef") + purpose.toString().toLowerCase() + "Mode_nestedLogit.csv");
            }
            for (Mode mode : Mode.getModes()) {
                String columnName = mode.toString(); //todo review this
                Map<String, Double> coefficients = new CoefficientsReader(dataSet, columnName, pathToFilePurpose).readCoefficients();
                modeCoefficients.put(mode, coefficients);
                if (mode == Mode.CAR_DRIVER) {
                    nestsByPurpose.add(new Tuple<>(EnumSet.of(Mode.CAR_DRIVER, Mode.CAR_PASSENGER), coefficients.get("nestingCoefficient")));
                } else if (mode == Mode.TRAIN) {
                    nestsByPurpose.add(new Tuple<>(EnumSet.of(Mode.TRAIN, Mode.TRAM_METRO, Mode.BUS), coefficients.get("nestingCoefficient")));
                } else if (mode == Mode.BIKE) {
                    nestsByPurpose.add(new Tuple<>(EnumSet.of(Mode.WALK, Mode.BIKE), coefficients.get("nestingCoefficient")));
                }
            }
            purposeModeCoefficients.put(purpose, modeCoefficients);
            nests.put(purpose, nestsByPurpose);
        }
        this.purposeModeCoefficients = purposeModeCoefficients;
        this.nests = nests;

    }

    public NestedLogitTourModeChoiceModel(DataSet dataSet, boolean runCalibration) {
        this(dataSet);
        this.updatedCalibrationFactors = new HashMap<>();
        for (Purpose purpose : Purpose.getAllPurposes()) {
            this.updatedCalibrationFactors.putIfAbsent(purpose, new HashMap<>());
            for (DayOfWeek dayOfWeek : DayOfWeek.values()) {
                this.updatedCalibrationFactors.get(purpose).putIfAbsent(dayOfWeek, new HashMap<>());
                for (Mode mode : Mode.getModes()) {
                    this.updatedCalibrationFactors.get(purpose).get(dayOfWeek).putIfAbsent(mode, 0.0);
                }
            }
        }
        this.runCalibration = runCalibration;
    }

    @Override
    public void chooseMode(Person person, Tour tour) {
        //This is only used for the simple mode choice that does not have coefficients by mode in separate files by purpose
    }

    @Override
    public void chooseMode(Person person, Tour tour, Purpose purpose) {

    }

    @Override
    public void checkCarAvailabilityAndChooseMode(Household household, Person person, Tour tour, Purpose purpose) {
        boolean carAvailable = Boolean.FALSE;
        Vehicle selectedVehicle = null;
        //TODO: how to define the car use start and end time
        int carUseStartTime_min = tour.getLegs().get(tour.getLegs().firstKey()).getNextActivity().getStartTime_min() - tour.getLegs().get(tour.getLegs().firstKey()).getTravelTime_min();
        int carUseEndTime_min = tour.getLegs().get(tour.getLegs().lastKey()).getPreviousActivity().getEndTime_min() + tour.getLegs().get(tour.getLegs().lastKey()).getTravelTime_min();

        for (Vehicle vehicle : household.getVehicles()) {
            if (vehicle instanceof Car) {
                carAvailable = ((Car) vehicle).getBlockedTimeOfWeek().isAvailable(carUseStartTime_min, carUseEndTime_min);
                if (carAvailable) {
                    selectedVehicle = vehicle;
                    break;
                }
            }
        }

        Mode selectedMode = chooseMode(person, tour, purpose, carAvailable);
        if (selectedVehicle != null & selectedMode.equals(Mode.CAR_DRIVER)) {
            ((Car) selectedVehicle).getBlockedTimeOfWeek().blockTime(carUseStartTime_min, carUseEndTime_min);
            tour.setCar(selectedVehicle);
        }
    }

    @Override
    public Mode chooseMode(Person person, Tour tour, Purpose purpose, Boolean carAvailable) {
        Household household = person.getHousehold();
        EnumMap<Mode, Double> utilities = new EnumMap<Mode, Double>(Mode.class);
        for (Mode mode : Mode.getModes()) {
            if (mode == Mode.CAR_DRIVER && !carAvailable) {
                utilities.put(mode, Double.NEGATIVE_INFINITY);
            } else {
                utilities.put(mode, calculateUtilityForThisMode(person, tour, purpose, mode, household));
            }
        }


        if (utilities == null) return null;
        EnumMap<Mode, Double> probabilities = logitTools.getProbabilities(utilities, nests.get(purpose));


        final Mode selected = MitoUtil.select(probabilities, AbitUtils.getRandomObject());

        tour.getLegs().values().forEach(leg -> leg.setLegMode(selected));
        tour.setTourMode(selected);
        return selected;

    }

    private double calculateUtilityForThisMode(Person person, Tour tour, Purpose purpose, Mode mode,
                                               Household household) {
        // Intercept
        double utility = purposeModeCoefficients.get(purpose).get(mode).get("INTERCEPT");

        // Sex
        if (person.getGender().equals(Gender.FEMALE)) {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.female", 0.);
        }

        // Age
        if (person.getAge() < 19) {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.age_gr_1", 0.);
        } else if (person.getAge() < 30) {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.age_gr_2", 0.);
        } else if (person.getAge() < 50) {
            utility += 0;
        } else if (person.getAge() < 70) {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.age_gr_45", 0.);
        } else {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.age_gr_6", 0.);
        }

        // Mobility restriction
        if (!person.getDisability().equals(Disability.WITHOUT)) {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mobilityRestricted", 0.);
        }

        // Household size
        utility += person.getHousehold().getPersons().size() * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.size", 0.);

        // Household Children
        int numChildren = (int) person.getHousehold().getPersons().stream().filter(p -> p.getAge() <= 18).count();

        if (numChildren > 0) {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.children", 0.);
        }

        // Household BBSR Region Type
        RegioStaR7 regionType = dataSet.getZones().get(person.getHousehold().getLocation().getZoneId()).getRegioStaR7Type();
        if (regionType.equals(RegioStaR7.URBAN_METROPOLIS) || regionType.equals(RegioStaR7.URBAN_REGIOPOLIS)) {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.regionType_12", 0.);
        } else if (regionType.equals(RegioStaR7.URBAN_MEDIUM_SIZED_CITY) || regionType.equals(RegioStaR7.URBAN_PROVINCIAL)) {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.regionType_34", 0.);
        } else if (regionType.equals(RegioStaR7.RURAL_CENTRAL_CITY) || regionType.equals(RegioStaR7.RURAL_URBAN_AREA)) {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.regionType_56", 0.);
        } else {
            utility += 0;
        }

        // Urban or rural
        RegioStaR2 regionType2 = dataSet.getZones().get(person.getHousehold().getLocation().getZoneId()).getRegioStaR2Type();
        if (regionType2.equals(RegioStaR2.URBAN)) {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.urban", 0.);
        } else {
            utility += 0;
        }

        //generalized costs
        EnumMap<Mode, Double> generalizedCosts = calculateGeneralizedCosts(purpose, household, tour);

        double gc = generalizedCosts.get(mode);
        utility += gc * purposeModeCoefficients.get(purpose).get(mode).get("gc");

        //number of days with work or education tours
        int numDaysWork = 0;
        int numDaysEducation = 0;
        int numDaysMandatory = 0;

        final List<Tour> tourList = person.getPlan().getTours().values().stream().filter(tourMandatory -> Purpose.getMandatoryPurposes().contains(tour.getMainActivity().getPurpose())).collect(Collectors.toList());

        int[] daysOfWork = new int[]{0, 0, 0, 0, 0, 0, 0};
        int[] daysOfEducation = new int[]{0, 0, 0, 0, 0, 0, 0};

        for (Tour tourMandatory : tourList) {

            if (tourMandatory.getMainActivity().getPurpose().equals(Purpose.WORK)) {
                int dayOfWeek = tourMandatory.getMainActivity().getDayOfWeek().getValue();
                if (daysOfWork[dayOfWeek - 1] == 0) {
                    daysOfWork[dayOfWeek - 1] = 1;
                }
            } else {
                int dayOfWeek = tourMandatory.getMainActivity().getDayOfWeek().getValue();
                if (daysOfEducation[dayOfWeek - 1] == 0) {
                    daysOfEducation[dayOfWeek - 1] = 1;
                }
            }
        }

        numDaysWork = Arrays.stream(daysOfWork).sum();
        numDaysEducation = Arrays.stream(daysOfEducation).sum();
        numDaysMandatory = numDaysWork + numDaysEducation;

        utility += numDaysWork * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("num_days_edu", 0.);
        utility += numDaysEducation * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("num_days_work", 0.);
        utility += numDaysMandatory * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("num_days_mand", 0.);

        //tour purpose
        if (purpose.equals(Purpose.EDUCATION)) {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("tour.purpose_education", 0.);
        } else {
            utility += 0;
        }

        //number of stops on tour
        if (tour.getActivities().size() >= 2) {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("tour.stops2orMore", 0.);
        } else {
            utility += 0;
        }


        //habitual mode
        switch (person.getHabitualMode()) {
            case CAR_DRIVER:
                utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mainCommuteMode_occupied_carD", 0.);
                break;
            case CAR_PASSENGER:
                utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mainCommuteMode_occupied_carP", 0.);
                break;
            case BIKE:
                utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mainCommuteMode_occupied_cycle", 0.);
                break;
            case PT:
                utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mainCommuteMode_occupied_PT", 0.);
                break;
            case WALK:
                utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mainCommuteMode_occupied_walk", 0.);
                break;
            case UNKNOWN:
                break;
        }


        if (purpose.equals(Purpose.WORK) || purpose.equals(Purpose.EDUCATION)) {
            utility += purposeModeCoefficients.get(purpose).get(mode).get("calibration_" + purpose.toString().toLowerCase() + "_" + tour.getMainActivity().getDayOfWeek().toString().toLowerCase());

        } else {

            utility += purposeModeCoefficients.get(purpose).get(mode).get("calibration_" + tour.getMainActivity().getDayOfWeek().toString().toLowerCase());
        }


        if (runCalibration) {
            utility = utility + updatedCalibrationFactors.get(tour.getMainActivity().getPurpose()).get(tour.getMainActivity().getDayOfWeek()).get(mode);
        }

        return utility;
    }

    //calculates generalized costs
    public EnumMap<Mode, Double> calculateGeneralizedCosts(Purpose purpose, Household household,
                                                           Tour tour) {

        double travelDistanceAuto = 0;

        for (Leg leg : tour.getLegs().values()) {
            travelDistanceAuto += dataSet.getTravelDistances().getTravelDistanceInMeters(leg.getPreviousActivity().getLocation(),
                    leg.getNextActivity().getLocation(), Mode.UNKNOWN, leg.getPreviousActivity().getEndTime_min());
        }

        travelDistanceAuto = travelDistanceAuto / 1000.;

        EnumMap<Mode, Double> tourTravelTimes = new EnumMap<Mode, Double>(Mode.class);
        for (Mode mode : Mode.getModes()) {
            if (mode == Mode.CAR_DRIVER || mode == Mode.CAR_PASSENGER) {
                double travelTimeCarDriver = 0;
                for (Leg leg : tour.getLegs().values()) {
                    travelTimeCarDriver += dataSet.getTravelTimes().getTravelTimeInMinutes(leg.getPreviousActivity().getLocation(),
                            leg.getNextActivity().getLocation(), Mode.CAR_DRIVER, leg.getPreviousActivity().getEndTime_min());
                }
                tourTravelTimes.put(mode, travelTimeCarDriver);
            } else if (mode == Mode.WALK || mode == Mode.BIKE) {
                double travelDistanceMode = 0;
                for (Leg leg : tour.getLegs().values()) {
                    travelDistanceMode += dataSet.getTravelDistances().getTravelDistanceInMeters(leg.getPreviousActivity().getLocation(),
                            leg.getNextActivity().getLocation(), Mode.UNKNOWN, leg.getPreviousActivity().getEndTime_min());
                }

                double travelTimeMode = (travelDistanceMode / 1000.) / (mode == Mode.WALK ? SPEED_WALK_KMH : SPEED_BICYCLE_KMH) * 60.;
                tourTravelTimes.put(mode, travelTimeMode);

            } else {
                double travelTimeMode = 0;
                for (Leg leg : tour.getLegs().values()) {
                    travelTimeMode += dataSet.getTravelTimes().getTravelTimeInMinutes(leg.getPreviousActivity().getLocation(),
                            leg.getNextActivity().getLocation(), mode, leg.getPreviousActivity().getEndTime_min());
                }
                tourTravelTimes.put(mode, travelTimeMode);
            }
        }

        double gcAutoD;
        double gcAutoP;
        double gcBus;
        double gcTrain;
        double gcTramMetro;

        double monthlyIncome_EUR = household.getPersons().stream().mapToInt(Person::getMonthlyIncome_eur).sum();

        //todo. Add VOT table with the coefficients and rename
        if (monthlyIncome_EUR <= 1500) {
            gcAutoD = tourTravelTimes.get(Mode.CAR_DRIVER) + (travelDistanceAuto * fuelCostEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.CAR_DRIVER).get("vot_less_or_equal_income_4");
            gcAutoP = tourTravelTimes.get(Mode.CAR_PASSENGER) + (travelDistanceAuto * fuelCostEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.CAR_PASSENGER).get("vot_less_or_equal_income_4");
            gcBus = tourTravelTimes.get(Mode.BUS) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.BUS).get("vot_less_or_equal_income_4");
            gcTrain = tourTravelTimes.get(Mode.TRAIN) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.TRAIN).get("vot_less_or_equal_income_4");
            gcTramMetro = tourTravelTimes.get(Mode.TRAM_METRO) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.TRAM_METRO).get("vot_less_or_equal_income_4");
        } else if (monthlyIncome_EUR <= 5000) {
            gcAutoD = tourTravelTimes.get(Mode.CAR_DRIVER) + (travelDistanceAuto * fuelCostEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.CAR_DRIVER).get("vot_income_5_to_10");
            gcAutoP = tourTravelTimes.get(Mode.CAR_PASSENGER) + (travelDistanceAuto * fuelCostEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.CAR_PASSENGER).get("vot_income_5_to_10");
            gcBus = tourTravelTimes.get(Mode.BUS) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.BUS).get("vot_income_5_to_10");
            gcTrain = tourTravelTimes.get(Mode.TRAIN) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.TRAIN).get("vot_income_5_to_10");
            gcTramMetro = tourTravelTimes.get(Mode.TRAM_METRO) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.TRAM_METRO).get("vot_income_5_to_10");
        } else {
            gcAutoD = tourTravelTimes.get(Mode.CAR_DRIVER) + (travelDistanceAuto * fuelCostEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.CAR_DRIVER).get("vot_income_greater_10");
            gcAutoP = tourTravelTimes.get(Mode.CAR_PASSENGER) + (travelDistanceAuto * fuelCostEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.CAR_PASSENGER).get("vot_income_greater_10");
            gcBus = tourTravelTimes.get(Mode.BUS) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.BUS).get("vot_income_greater_10");
            gcTrain = tourTravelTimes.get(Mode.TRAIN) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.TRAIN).get("vot_income_greater_10");
            gcTramMetro = tourTravelTimes.get(Mode.TRAM_METRO) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.TRAM_METRO).get("vot_income_greater_10");
        }

        EnumMap<Mode, Double> generalizedCosts = new EnumMap<Mode, Double>(Mode.class);

        if (purpose.equals(Purpose.ACCOMPANY)) {
            generalizedCosts.put(Mode.CAR_DRIVER, Math.log(gcAutoD));
            generalizedCosts.put(Mode.CAR_PASSENGER, Math.log(gcAutoP));
            generalizedCosts.put(Mode.BIKE, Math.log(tourTravelTimes.get(Mode.BIKE)));
            generalizedCosts.put(Mode.BUS, Math.log(gcBus));
            generalizedCosts.put(Mode.TRAIN, Math.log(gcTrain));
            generalizedCosts.put(Mode.TRAM_METRO, Math.log(gcTramMetro));
            generalizedCosts.put(Mode.WALK, Math.log(tourTravelTimes.get(Mode.WALK)));
        } else {
            generalizedCosts.put(Mode.CAR_DRIVER, gcAutoD);
            generalizedCosts.put(Mode.CAR_PASSENGER, gcAutoP);
            generalizedCosts.put(Mode.BIKE, tourTravelTimes.get(Mode.BIKE));
            generalizedCosts.put(Mode.BUS, gcBus);
            generalizedCosts.put(Mode.TRAIN, gcTrain);
            generalizedCosts.put(Mode.TRAM_METRO, gcTramMetro);
            generalizedCosts.put(Mode.WALK, tourTravelTimes.get(Mode.WALK));
        }
        return generalizedCosts;
    }

    public void updateCalibrationFactor(Map<Purpose, Map<DayOfWeek, Map<Mode, Double>>> newCalibrationFactors) {
        for (Purpose purpose : Purpose.getAllPurposes()) {
            for (DayOfWeek dayOfWeek : DayOfWeek.values()) {
                for (Mode mode : Mode.getModes()) {
                    double calibrationFactorFromLastIteration = this.updatedCalibrationFactors.get(purpose).get(dayOfWeek).get(mode);
                    double updatedCalibrationFactor = newCalibrationFactors.get(purpose).get(dayOfWeek).get(mode) + calibrationFactorFromLastIteration;
                    this.updatedCalibrationFactors.get(purpose).get(dayOfWeek).replace(mode, updatedCalibrationFactor);
                    logger.info("Calibration factor for " + purpose + "\t" + dayOfWeek + "\t" + "and " + mode + "\t" + ": " + updatedCalibrationFactor);
                }
            }
        }
    }

    public Map<Purpose, Map<Mode, Map<String, Double>>> obtainCoefficientsTable() {

        double originalCalibrationFactor = 0.0;
        double updatedCalibrationFactor = 0.0;
        double latestCalibrationFactor = 0.0;

        for (Purpose purpose : Purpose.getAllPurposes()) {
            for (Mode mode : Mode.getModes()) {
                for (DayOfWeek dayOfWeek : DayOfWeek.values()) {
                    if (purpose.equals(Purpose.WORK) || purpose.equals(Purpose.EDUCATION)) {
                        originalCalibrationFactor = this.purposeModeCoefficients.get(purpose).get(mode).get("calibration_" + purpose.toString().toLowerCase() + "_" + dayOfWeek.toString().toLowerCase());
                    } else {
                        originalCalibrationFactor = this.purposeModeCoefficients.get(purpose).get(mode).get("calibration_" + dayOfWeek.toString().toLowerCase());
                    }
                    updatedCalibrationFactor = updatedCalibrationFactors.get(purpose).get(dayOfWeek).get(mode);
                    latestCalibrationFactor = originalCalibrationFactor + updatedCalibrationFactor;
                    if (purpose.equals(Purpose.WORK) || purpose.equals(Purpose.EDUCATION)) {
                        purposeModeCoefficients.get(purpose).get(mode).replace("calibration_" + purpose.toString().toLowerCase() + "_" + dayOfWeek.toString().toLowerCase(), latestCalibrationFactor);
                    } else {
                        purposeModeCoefficients.get(purpose).get(mode).replace("calibration_" + dayOfWeek.toString().toLowerCase(), latestCalibrationFactor);
                    }
                }
            }
        }
        return this.purposeModeCoefficients;
    }


}
