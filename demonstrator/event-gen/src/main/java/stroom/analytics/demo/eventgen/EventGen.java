/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package stroom.analytics.demo.eventgen;

import jdk.jfr.Event;
import stroom.analytics.demo.eventgen.beans.*;

import java.io.File;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

import org.apache.commons.lang3.builder.ReflectionToStringBuilder;

import org.apache.commons.lang3.builder.ToStringStyle;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

public class EventGen {
    private final static String SEPARATOR = ", ";
    private final static int DAY_SECS = 86400;

    private final Global config;
    private final Random random;

    private long processingMs;

    private Map<Identity, List<Instance>> instances = new HashMap<Identity, List<Instance>>();
    private Map<String, Identity> identities = new HashMap<>();

    private Map <String, EventStream> streams = new HashMap<>();

    public EventGen (Global config){
        this.config = config;

        if (config.getRunDays() <= 0){
            throw new IllegalArgumentException("runDays property must be defined");
        }

        initStreams ();
        initIdentities();
        processingMs = System.currentTimeMillis() - (config.getRunDays() * DAY_SECS * 1000);

        random = new Random(config.getRandomSeed());
    }


    public void start (){
        createInstances();

        for (int day = 1; day <= config.getRunDays(); day++){
            System.out.println("Processing day " + day + " of " + config.getRunDays());
            processDay();
        }
    }

    private void initIdentities(){
        for (Identity identity : config.getIdentities()){
            identities.put(identity.getName(), identity);
        }
    }

    private void initStreams(){
        for (EventStream stream : config.getStreams()){
            streams.put(stream.getName(), stream);
        }
    }


    private void processDay (){
        long dayStart = processingMs;
        for (int sec = 0; sec < DAY_SECS; sec++){
            processingMs = dayStart + sec * 1000;

            generateEvents ();
        }
    }

    private void generateEvents(){
        for (Identity identity : instances.keySet()){
            for (Instance instance : instances.get(identity)){
                if (instance.getState() != null){
                    State state = identity.getState(instance.getState());

                    if (state.getTransitions() == null)
                        continue;

                    for (Transition transition : state.getTransitions()){
                        double maxValForTransition = 0.693 / transition.getHalfLifeSecs();
                        if (random.nextDouble() <= maxValForTransition)
                        {
                            fireEvent (instance, transition);
                            break;
                        }
                    }

                }
            }
        }
    }

    private void fireEvent (Instance instance, Transition transition){

        if (transition.getEventStream() != null) {
            StringBuilder builder = new StringBuilder();
            builder.append(transition.getEventStream());
            builder.append(SEPARATOR);

            builder.append(ZonedDateTime.ofInstant(Instant.ofEpochMilli(processingMs),
                    ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT));
            builder.append(SEPARATOR);

            builder.append(transition.getName());

            EventStream stream = streams.get(transition.getEventStream());
            if (stream.getIdentifiedObjects() != null){
                for (String field : stream.getIdentifiedObjects()){
                    builder.append(SEPARATOR);
                    if (field.equals(instance.getType().getName())){
                        builder.append(instance.getName());
                    }
                    else {
                        String otherInstance = findRelatedInstance (instance, field);
                        builder.append(otherInstance);
                    }
                }
            }

            System.out.println(builder.toString());
        }

        if (transition.getTo() != null)
            instance.setState(transition.getTo());
    }

    private String findRelatedInstance (Instance instance, String otherTypeName){
        if (instance.getAffinities().get(otherTypeName) != null){
            return instance.getAffinities().get(otherTypeName).getName();
        }

        Identity otherType = identities.get (otherTypeName);
        int numberOfInstances = otherType.getCount();

        Instance toAssociate = instances.get(otherType).get(random.nextInt(numberOfInstances));

        instance.getAffinities().put(otherType, toAssociate);

        return toAssociate.getName();
    }

    private void createInstances(){
        for (int type = 0; type < config.getIdentities().length; type++){
            Identity thisIdentity = config.getIdentities()[type];
            List<Instance> theseInstances = new ArrayList<>();
            instances.put(thisIdentity, theseInstances);
            for (int instance = 1; instance <= thisIdentity.getCount(); instance++){
                Instance thisInstance = new Instance(thisIdentity, thisIdentity.getName() + instance);
                theseInstances.add(thisInstance);
                if (thisIdentity.getStates() != null){
                    String state = chooseInitialState (thisIdentity.getStates());
                    thisInstance.setState(state);
                }
            }
        }
    }

    private String chooseInitialState (State[] states){
        float val = random.nextFloat();
        float minVal = 0.0f;
        for (int s = 0; s < states.length; s++){
            float maxVal = minVal + states[s].getInitialLikelihood();
            if (val <= maxVal)
                return states[s].getName();
            minVal = maxVal;
        }
        throw new IllegalArgumentException("State initial likelihood doesn't add up to 1.0");
    }

    public static void main(String[] args) {

        if (args.length != 1){
            System.out.println ("Usage: EventGen <config yaml file>");
            System.exit(1);
        }

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

        try {
            File configFile = new File (args[0]);
            if (!configFile.exists()) {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                configFile = new File(loader.getResource(args[0]).getFile());
            }

            if (!configFile.exists()){
                System.err.println ("FATAL: Unable to read file " + args[0]);
                System.exit(2);
            }

            Global global = mapper.readValue(configFile, Global.class);

            EventGen eventGen = new EventGen(global);

            eventGen.start();

        } catch (Exception e) {

            // TODO Auto-generated catch block

            e.printStackTrace();

        }

    }
}
