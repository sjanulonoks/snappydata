io.snappydata.hydra.cluster.SnappyPrms-waitTimeBeforeNextCycleVM = 120; //how long to wait before nodes will be cycled.
io.snappydata.hydra.cluster.SnappyPrms-cycleVms = true;

hydra.Prms-totalTaskTimeSec           = 240;

util.StopStartPrms-stopModes = NICE_KILL;
util.StopStartPrms-numVMsToStop = RANGE 1 ${numVMsToStop} EGNAR;

INITTASK    taskClass   = util.StopStartVMs  taskMethod = StopStart_initTask
            threadGroups = snappyThreads, locatorThreads, snappyStoreThreads, leadThreads;

TASK       taskClass   = io.snappydata.hydra.cluster.SnappyTest taskMethod  = HydraTask_cycleLeadVM
           maxThreads = 1
           startInterval = 60
           //weight = 40
           threadGroups = snappyStoreThreads
           maxTimesToRun = 3
           ;

CLOSETASK  taskClass   = io.snappydata.hydra.cluster.SnappyTest taskMethod  = restoreLeadConfigData
           threadGroups = snappyThreads;
