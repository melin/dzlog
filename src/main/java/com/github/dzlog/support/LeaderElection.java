package com.github.dzlog.support;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * @author melin 2021/8/26 4:30 下午
 */
public class LeaderElection {

    private static final Logger logger = LoggerFactory.getLogger(LeaderElection.class);

    private static final int WAIT_SECONDS = 1;

    private RedissonClient redissonClient;

    private RLock leaderLock;

    private boolean stop = false;

    private boolean isInit = false;

    private Object masterLock = new Object();

    private Object initLock = new Object();

    private ElectionThread electionThread = new ElectionThread();

    public void tryHold(String leaderName) {
        leaderLock = redissonClient.getLock(leaderName);
        electionThread.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown()));
    }

    public boolean isMaster() {
        synchronized (initLock) {
            if (!isInit) {
                try {
                    initLock.wait();
                } catch (InterruptedException e) {

                }
            }
        }
        return electionThread.isMaster();
    }

    public void shutdown() {
        stop = true;
        try {
            synchronized (masterLock) {
                masterLock.notifyAll();
            }
            electionThread.join();
        } catch (InterruptedException e) {
        }
        logger.info("shutdown and give up leadership");
    }

    class ElectionThread extends Thread {

        private boolean isMaster = false;

        public ElectionThread() {
            setName("leader-election");
        }

        @Override
        public void run() {
            while (!stop) {
                try {
                    if (isMaster) {
                        synchronized (masterLock) {
                            if (isInit) {
                                masterLock.wait();
                            } else {
                                masterLock.wait(Duration.ofSeconds(WAIT_SECONDS).toMillis());
                            }
                        }
                    } else {
                        isMaster = leaderLock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS);
                        if (isMaster) {
                            logger.info("get leadership");
                        }
                    }
                } catch (InterruptedException e) {

                } finally {
                    synchronized (initLock) {
                        if (!isInit) {
                            initLock.notifyAll();
                            isInit = true;
                        }
                    }
                }
            }

            if (leaderLock.isLocked() && leaderLock.isHeldByCurrentThread()) {
                leaderLock.unlock();
            }

            if (isMaster) {
                isMaster = false;
            }
        }

        public boolean isMaster() {
            return isMaster;
        }
    }

    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }
}
