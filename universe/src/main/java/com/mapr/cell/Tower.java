package com.mapr.cell;

import akka.actor.UntypedActor;
import com.google.common.io.Resources;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.Random;

/**
 * Each tower is an actor that receives messages from Callers.
 */
public class Tower extends UntypedActor {
    private static final double MINIMUM_RECEIVE_POWER = -100;
    private final Random rand;

    private Antenna ax;
    private String id;

    final String TOPIC_NAME = "/telco:tower%s";

    private KafkaProducer<String, String> producer;

    public Tower() {

        try (InputStream props = Resources.getResource("producer.conf").openStream()) {
            Properties properties = new Properties();
            properties.load(props);
            producer = new KafkaProducer<>(properties);
        } catch (IOException e) {
            e.printStackTrace();
        }

        rand = new Random();
        ax = Antenna.omni(rand.nextDouble() * 20e3, rand.nextDouble() * 20e3);
        ax.setPower(100, 1);
        id = String.format("%08x", rand.nextInt());
    }

    @Override
    public void onReceive(Object message) throws Exception {
        if (message instanceof Messages.Setup) {
            System.out.printf("Setup complete for tower %s\n", id);
        } else if (message instanceof Messages.SignalReportRequest) {
            Messages.SignalReportRequest m = (Messages.SignalReportRequest) message;
            double r = ax.distance(m.x, m.y);
            double p = ax.power(m.x, m.y);
            if (p > MINIMUM_RECEIVE_POWER) {
                m.source.tell(new Messages.SignalReport(r, p, id, getSelf()));
            }
        } else if (message instanceof Messages.Hello) {
            Messages.Hello helloMessage = (Messages.Hello) message;
            double u = rand.nextDouble();
            if (u < 0.8) {
                System.out.printf("Start call caller %s to tower %s\n", ((Messages.Hello) message).cdr.getCallerId(), id );
                helloMessage.caller.tell(new Messages.Connect(id, getSelf()));
                System.out.println("Connect CDR sent: " + ((Messages.Hello) message).cdr.toJSONObject());
                sendToStream(((Messages.Hello) message).cdr.toJSONObject());
                if (((Messages.Hello) message).reconnect) {
                    ((Messages.Hello) message).cdr.setState(CDR.State.RECONNECT);
                    System.out.println("Reconnect CDR sent: " + ((Messages.Hello) message).cdr.toJSONObject());
                    sendToStream(((Messages.Hello) message).cdr.toJSONObject());
                }
            } else if (u < 0.95) {
                System.out.printf("Failed call caller %s to tower %s\n", ((Messages.Hello) message).cdr.getCallerId(), id );
                ((Messages.Hello) message).cdr.setState(CDR.State.FAIL);
                sendToStream(((Messages.Hello) message).cdr.toJSONObject());
                helloMessage.caller.tell(new Messages.Fail(id));
            } else {
                // ignore request occasionally ... it will make the caller stronger
            }
        } else if (message instanceof Messages.Disconnect) {
            System.out.printf("Finished call caller %s to tower %s\n", ((Messages.Disconnect) message).callerId, id );
            System.out.println("Finished CDR sent: " + ((Messages.Disconnect) message).cdr.toJSONObject());
            sendToStream(((Messages.Disconnect) message).cdr.toJSONObject());
        } else {
            unhandled(message);
        }
    }

    private void sendToStream(JSONObject jsonObject) {
        producer.send(new ProducerRecord<String, String>(
                String.format(TOPIC_NAME, id),
                jsonObject.toString()), new Callback() {
            @Override
            public void onCompletion(RecordMetadata recordMetadata, Exception e) {
                if (e != null) {
                    System.err.println("Exception occurred while sending :(");
                    System.err.println(e.toString());
                    return;
                }
            }
        });
        producer.flush();
    }
}