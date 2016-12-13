package sonata.kernel.placement.monitor;

import com.google.common.collect.Lists;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.log4j.Logger;
import sonata.kernel.placement.MessageQueue;
import sonata.kernel.placement.PlacementConfigLoader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class MonitorManager implements Runnable {

    final static Logger logger = Logger.getLogger(MonitorManager.class);

    public static List<FunctionMonitor> monitors = new ArrayList<FunctionMonitor>();

    public static long intervalMillis = 2000;
    public static volatile boolean active = false;

    public static ConnectingIOReactor ioreactor;
    public static PoolingNHttpClientConnectionManager pool;
    public static CloseableHttpAsyncClient asyncClient;


    public static ReentrantLock monitoringLock = new ReentrantLock();
    public static Condition pendingRequestsCondition = monitoringLock.newCondition();
    private static int pendingRequests = 0;


    public static Thread monitorThread;

    static {

        intervalMillis = PlacementConfigLoader.loadPlacementConfig().getMonitorIntervalMs();

        try {
            ioreactor = new DefaultConnectingIOReactor(IOReactorConfig.custom().setIoThreadCount(4).build());
        } catch (IOReactorException e) {
            e.printStackTrace();
        }
        pool = new PoolingNHttpClientConnectionManager(ioreactor);
        pool.setDefaultMaxPerRoute(10);

        asyncClient = HttpAsyncClientBuilder.create().setConnectionManager(pool).build();
        asyncClient.start();
    }



    public static void startMonitor(){
        if(monitorThread != null)
            logger.debug("MonitorThread not null!!!");
        if(monitorThread == null) {
            active = true;

            if(monitors.size()>pool.getDefaultMaxPerRoute())
                pool.setDefaultMaxPerRoute(monitors.size()+5);

            monitorThread = new Thread(new MonitorManager(), "MonitorManagerThread");
            monitorThread.start();
        }
    }

    public static void stopMonitor(){
        Thread oldThread = monitorThread;
        if(oldThread != null) {

            active = false;

            try {
                oldThread.join(15000);

            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                if(oldThread.getState() != Thread.State.TERMINATED) {
                    logger.debug("Thread is stuck, going to interrupt it.");
                    monitorThread.interrupt();
                }
            }
        }
    }

    public static void addAndStartMonitor(FunctionMonitor monitor){
        synchronized (monitors) {
            monitors.add(monitor);
        }
        startMonitor();
    }

    public static void addAndStartMonitor(List<FunctionMonitor> monitorList){
        synchronized (monitors) {
            monitors.addAll(monitorList);
        }
        startMonitor();
    }

    public static void updateMonitors(List<FunctionMonitor> addMonitors, List<FunctionMonitor> removeMonitors){
        synchronized (monitors){
            monitors.removeAll(removeMonitors);
            monitors.addAll(addMonitors);
            if(monitors.size()>pool.getDefaultMaxPerRoute())
                pool.setDefaultMaxPerRoute(monitors.size()+5);
        }
    }

    public static void stopAndRemoveAllMonitors(){
        stopMonitor();
        synchronized (monitors) {
            monitors.clear();
        }
    }

    public static void closeConnectionPool(){
        try {
            pool.shutdown();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void requestFinished(){
        monitoringLock.lock();

        pendingRequests--;
        pendingRequestsCondition.signal();

        monitoringLock.unlock();
    }

    public static List<FunctionMonitor> getMonitorListCopy(){
        synchronized (monitors){
            return new ArrayList<FunctionMonitor>(monitors);
        }
    }

    public static MessageQueue.MessageQueueMonitorData getMonitorData(){
        Map<String,List<MonitorStats>> statsHistoryMap;
        synchronized (monitors) {
            statsHistoryMap = new HashMap<String,List<MonitorStats>>();
            FunctionMonitor monitor;
            List<MonitorStats> statsHistory;

            for (int i=0; i<monitors.size(); i++){
                monitor = monitors.get(i);
                statsHistory = new ArrayList<MonitorStats>();
                statsHistory.addAll(monitor.statsList);
                statsHistoryMap.put(monitor.instanceName, statsHistory);
            }
        }
        return new MessageQueue.MessageQueueMonitorData(statsHistoryMap);
    }

    private MonitorManager(){

    }

    public void run(){



        monitoringLock.lock();

        logger.info("Start monitoring");

        while(active){
            try {

                pendingRequests = monitors.size();

                monitoringLock.unlock();

                synchronized (monitors) {
                    for (int i=0; i<monitors.size(); i++){
                        monitors.get(i).requestMonitorStats(asyncClient);
                    }
                }

                monitoringLock.lock();

                while(pendingRequests > 0) {
                    pendingRequestsCondition.await(); // TODO: timeout?
                }

                MessageQueue.get_deploymentQ().add(getMonitorData());

                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                e.printStackTrace();
                logger.error(e);
                logger.error(e);
                for(int i=0; i<monitors.size(); i++) {
                    FunctionMonitor monitor = monitors.get(i);
                    if(monitor!=null)
                        monitor.stopMonitorRequest();
                }
            }
        }

        monitoringLock.unlock();

        logger.info("Stop monitoring");

        monitorThread = null;
    }




}
