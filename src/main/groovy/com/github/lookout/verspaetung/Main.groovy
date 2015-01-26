package com.github.lookout.verspaetung

import com.github.lookout.verspaetung.zk.BrokerTreeWatcher
import com.github.lookout.verspaetung.zk.StandardTreeWatcher

import java.util.concurrent.ConcurrentHashMap
import groovy.transform.TypeChecked

import com.timgroup.statsd.StatsDClient
import com.timgroup.statsd.NonBlockingDogStatsDClient

import org.apache.commons.cli.*
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.cache.TreeCache
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class Main {
    private static final String METRICS_PREFIX = 'verspaetung'

    private static StatsDClient statsd
    private static Logger logger

    static void main(String[] args) {
        String zookeeperHosts = 'localhost:2181'
        String statsdHost = 'localhost'
        Integer statsdPort = 8125

        CommandLine cli = parseCommandLine(args)

        if (cli.hasOption('z')) {
            zookeeperHosts = cli.getOptionValue('z')
        }

        if (cli.hasOption('H')) {
            statsdHost = cli.getOptionValue('H')
        }

        if (cli.hasOption('p')) {
            statsdPort = cli.getOptionValue('p')
        }

        logger = LoggerFactory.getLogger(Main.class)
        logger.info("Running with: ${args}")
        logger.warn("Using: zookeepers=${zookeeperHosts} statsd=${statsdHost}:${statsdPort}")

        ExponentialBackoffRetry retry = new ExponentialBackoffRetry(1000, 3)
        CuratorFramework client = CuratorFrameworkFactory.newClient(zookeeperHosts, retry)
        ConcurrentHashMap<TopicPartition, List<zk.ConsumerOffset>> consumers = new ConcurrentHashMap()

        statsd = new NonBlockingDogStatsDClient(METRICS_PREFIX, statsdHost, statsdPort)

        client.start()

        TreeCache cache = new TreeCache(client, '/consumers')

        KafkaPoller poller = new KafkaPoller(consumers)
        StandardTreeWatcher consumerWatcher = new StandardTreeWatcher(consumers)
        consumerWatcher.onInitComplete << {
            logger.info("standard consumers initialized to ${consumers.size()} (topic, partition) tuples")
        }

        BrokerTreeWatcher brokerWatcher = new BrokerTreeWatcher(client)
        brokerWatcher.onBrokerUpdates << { brokers ->
            poller.refresh(brokers)
        }

        cache.listenable.addListener(consumerWatcher)

        poller.onDelta << { String groupName, TopicPartition tp, Long delta ->
            statsd.recordGaugeValue(tp.topic, delta, [
                                                        'topic' : tp.topic,
                                                        'partition' : tp.partition,
                                                        'consumer-group' : groupName
                ])
        }

        poller.start()
        brokerWatcher.start()
        cache.start()

        logger.info("Started wait loop...")

        while (true) {
            statsd?.recordGaugeValue('heartbeat', 1)
            Thread.sleep(1 * 1000)
        }

        logger.info("exiting..")
        poller.die()
        poller.join()
        return
    }

    static Options createCLI() {
        Options options = new Options()

        Option zookeeper = OptionBuilder.withArgName('HOSTS')
                                        .hasArg()
                                        .withDescription('Comma separated list of Zookeeper hosts (e.g. localhost:2181)')
                                        .withLongOpt('zookeeper')
                                        .withValueSeparator(',' as char)
                                        .create('z')

        Option statsd_host = OptionBuilder.withArgName('STATSD')
                                        .hasArg()
                                        .withType(String)
                                        .withDescription('Hostname for a statsd instance (defaults to localhost)')
                                        .withLongOpt('statsd-host')
                                        .create('H')

        Option statsd_port = OptionBuilder.withArgName('PORT')
                                          .hasArg()
                                          .withType(Integer)
                                          .withDescription('Port for the statsd instance (defaults to 8125)')
                                          .withLongOpt('statsd-port')
                                          .create('p')

        options.addOption(zookeeper)
        options.addOption(statsd_host)
        options.addOption(statsd_port)

        return options
    }

    static CommandLine parseCommandLine(String[] args) {
        Options options = createCLI()
        PosixParser parser = new PosixParser()

        try {
            return parser.parse(options, args)
        }
        catch (MissingOptionException|UnrecognizedOptionException ex) {
            HelpFormatter formatter = new HelpFormatter()
            println ex.message
            formatter.printHelp('verspaetung', options)
            System.exit(1)
        }
    }
}
