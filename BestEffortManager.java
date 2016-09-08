//25Feb02[Wofford] - Sudafed renamed Congestion Advisory.
//15Feb2002[Wu] Repackaged
//04Feb 2002 [Wofford] Created.

package org.saamnet.saam.server;

import org.saamnet.saam.gui.MAGMAAdminGui;
import org.saamnet.saam.net.*;
import org.saamnet.saam.message.EdgeNotification;
import org.saamnet.saam.message.CongestionAdvisory;
import org.saamnet.saam.message.FlowRoutingTableEntry;
import org.saamnet.saam.agent.router.FlowRoutingTable;

import java.util.Enumeration;
import java.util.Vector;
import java.net.*;

/**
 * BestEffortManager (BEM) is the intelligence within the SAAM server that manages
 * best effort traffic.  It continuously monitors LSA's through one of two modes:
 * proactive or reactive.  Absent global congestion, BEM communicates with BE
 * router agents to handle local congestion.  During global congestion, BEM takes
 * measures to enforce fairness.
 */
public class BestEffortManager
{
  //when a BE path is expired, the period it will remain inactive
  public final static long PATH_EXPIRATION_TIME = 1800000;//30 minutes

  //references required for operation
  private BasePIB myBasePIB;
  private Server myServer;
  
  private MAGMAAdminGui gui;
  
  private boolean globalCongestion;//is it occurring?
  
  private long localResolutionTimeout;//allow local resolution to take place
  private boolean lrtInitialized;//tracks initialization of localResolutionTimeout
  private long timeLastActionTaken;//the last time an active measure was taken
  private long timeLastCongestion;//the last time congestion was noted
	private long timeLastSwitchback;//the last time a switchback was performed
  
  //statistics used for fairness measures
  private double meanLossRate;
  private double stdLossRateDev;
  
  //Vectors that store routerID's and interface addresses that
  //are registered for best effort traffic BY THEIR STRING REPRESENTATION.
  Vector vBestEffortRouters = new Vector();
  Vector vBestEffortDestAdds = new Vector();
	
	//used in bePathAdmin()
	private static final byte UNEXPIRE_PATHS = 0;
  private static final byte GET_PATHS = 1;
	private static final byte UPDATE_LOSS_RATE = 2;
	private static final byte RECLAIM_PATHS = 7;
	
	//used in beNodePairAdmin()
	private static final byte DEPLOY_INITIAL_PATHS = 3;
	private static final byte GET_LOSS_RATES = 4;
	private static final byte ROB_IF_RICH = 5;
	private static final byte GIVE_IF_POOR = 6;	

  /**
   * CONSTRUCTOR
   * @param   basepib  required reference
   * @param   server   required reference
   */
  BestEffortManager(BasePIB basepib, Server server)
  {
    myBasePIB = basepib;
    myServer = server;
    
    // Create Gui for PIB display during generation.
    gui = new MAGMAAdminGui("Best Effort Manager", server);
    server.getControlExec().addMagmaGui(gui);
		
		gui.sendText("Initializing...");
    
    globalCongestion = false;
    lrtInitialized = false;
		timeLastSwitchback = 0;

		gui.sendText("initialized.");
		
  }
  

  /**
   * Processes EdgeNotification messages.
   * @param   edgeNotif  the message
   */
  protected void processEdgeNotification (EdgeNotification edgeNotif)
  {
    //initialize localResolutionTimeout variable
    if (!lrtInitialized)
    {
      localResolutionTimeout = 10 * myServer.getAC_cyclePeriod();
      lrtInitialized = true;
			gui.sendText("\nLocal resolution timeout is " + localResolutionTimeout + "ms.");
    }
  
    int count = 0;//used below to figure out how much information is new
  
    gui.sendText("\nProcessing edge notification.");
  
    IPv6Address interfaceAddress = edgeNotif.getEdgeInterfaceAddress();
    
    //Xie-darpa
    BasePIB.InterfaceInfo edgeInterfaceInfo = (BasePIB.InterfaceInfo) myBasePIB.htInterfaces.get(interfaceAddress.toString());
    if (edgeInterfaceInfo == null)
    {
      gui.sendText("\n  PIB is not ready; quit processing the edge notification message.");
      return;
    }
    
    int nodeID = edgeInterfaceInfo.getNodeID().intValue();
    IPv6Address routerID = (IPv6Address) myBasePIB.htNodeIDtoRouterID.get(new Integer (nodeID));

    //is this a newly discovered edge router?
    if (!(vBestEffortRouters.contains(routerID.toString())))
    {
      gui.sendText("Adding " + routerID.toString() + " to edge routers vector.");
      vBestEffortRouters.add(routerID.toString());
      count++;
    }
    //is this a newly discovered destinaton interface?
    if (!(vBestEffortDestAdds.contains(interfaceAddress.toString())))
    {
      gui.sendText("Adding " + interfaceAddress.toString() + " to edge interfaces vector.");
      vBestEffortDestAdds.add(interfaceAddress.toString());
      count++;
    }
		
		if (count > 0)
		{
	    gui.sendText("Updating best effort topology...");
	    updateBEtopology();    
	    gui.sendText("Completed updating best effort topology.");
		}
		else
		{
			gui.sendText("No new information; best effort topology still accurate.");
		}     
  }//end processEdgeNotification()
  

  /**
   * When global congestion is absent, reactive monitoring takes place.
   * @param   path      the path being observed
   * @param   lossRate  the best effort loss rate on that path
   */
  protected void reactiveMonitor(BasePIB.Path path, short lossRate)
  {
    if (lossRate > myBasePIB.thresholdLossRate)
    {    
      unexpireBEpaths();//see if any expired paths are due for reuse
    
      switch (path.bestEffortTrafficCondition)
      {
      case BasePIB.Path.GRAY:
        break;
      
      //this is a case of new congestion  
      case BasePIB.Path.GREEN:
        int firstNodeID = path.getSrcNodeID();
				int lastNodeID = path.getDestNodeID();
				if (path == alternatePathForThisNodePair(firstNodeID, lastNodeID))
				{
					BasePIB.Path primaryPath = primaryPathForThisNodePair(firstNodeID, lastNodeID);
					if (primaryPath.bestEffortTrafficCondition == BasePIB.Path.GREEN)
					{
						gui.sendText("\nNew congestion on an alternate path while primary path lossless.");
						switchback(firstNodeID, lastNodeID);
					}
					else
					{
						path.newCongestion();
					}
				}
				else
				{
	        IPv6Address routerID = (IPv6Address) myBasePIB.htNodeIDtoRouterID.get(new Integer(firstNodeID));
	        myServer.sendCongestionAdvisory(routerID, path.getPathID().intValue(), CongestionAdvisory.YELLOW);
	        path.newCongestion();
					gui.sendText("\nNew congestion on primary path " + path.getPathID().intValue() + ".");
					gui.sendText("Congestion Advisory YELLOW sent to node " + firstNodeID + ".");
				}
        break;
      
      //if local resolution has failed, deploy new paths or initiate global congestion procedures
      case BasePIB.Path.YELLOW:
        boolean noLocalResolutionPossible = false;
        if ((System.currentTimeMillis() - path.timeLastAdvisorySent) > (localResolutionTimeout * myBasePIB.timeScale))
        {  
          noLocalResolutionPossible = true;
          firstNodeID = path.getSrcNodeID();      
          IPv6Address srcRouterID = (IPv6Address) myBasePIB.htNodeIDtoRouterID.get(new Integer(firstNodeID));
          lastNodeID = ((Integer) (path.getNodeSequence().firstElement())).intValue();
          IPv6Address destRouterID = (IPv6Address) myBasePIB.htNodeIDtoRouterID.get(new Integer(lastNodeID));
          BasePIB.Path bePath1 = myBasePIB.routingAlgorithm.findPath(srcRouterID,
                                                                     destRouterID,
                                                                     null,
                                                                     myBasePIB.routingAlgorithm.SHORTEST_WIDEST_LEAST_CONGESTED_PATH);                                            
          if (bePath1 != null)
          {
						gui.sendText("\nCongestion bypass initiated for nodes " + firstNodeID + " to " + lastNodeID + ".");
            noLocalResolutionPossible = false;
            Integer bePathID1 = bePath1.getPathID();
						Integer bePathID2 = null;
						gui.sendText("Deploying path " + bePathID1 + " as the new primary path.");
            if (!bePath1.bCreated)
            {
              myBasePIB.setupPath(bePath1, bePathID1.intValue(), FlowRoutingTableEntry.INSTALLED_FOR_BE);
              bePath1.bCreated = true;
            }

            expireBEpaths(firstNodeID, lastNodeID);
            myServer.sendCongestionAdvisory(srcRouterID, path.getPathID().intValue(), CongestionAdvisory.GREEN);
						gui.sendText("Congestion Advisory GREEN sent to node " + firstNodeID + ".");
            BasePIB.Path bePath2 = myBasePIB.routingAlgorithm.findPath(srcRouterID,
                                                                       destRouterID,
                                                                       bePath1,
                                                                       myBasePIB.routingAlgorithm.SHORTEST_WIDEST_MOST_DISJOINT_PATH);
            if (bePath2 != null)
            {
							bePathID2 = bePath2.getPathID();
							gui.sendText("Deploying path " + bePathID2 + " as the new alternate path.");
              if (!bePath2.bCreated)
              {
                myBasePIB.setupPath(bePath2, bePathID2.intValue(), FlowRoutingTableEntry.INSTALLED_FOR_BE);
                bePath2.bCreated = true;
              }
            }
            else
            {
							bePathID2 = bePathID1;
            }//end if
						sendTableEntries(srcRouterID, destRouterID, bePathID1.intValue(), bePathID2.intValue());                            
          }//end if
        }//end if
        if ((noLocalResolutionPossible) && (!globalCongestion))
        {
					gui.sendText("\nWARNING!\nWARNING!\nWARNING!");
					gui.sendText("G L O B A L   C O N G E S T I O N");
					gui.sendText("Initiating global congestion resolution procedures.");
					initiateGlobalCongestionResolution();
        }
        break;
      
      //no action necessary; the path's been retired
      case BasePIB.Path.RED:
        break;
        
      default:
        break;
      }        
    }
    //has the previous congestion cleared?
    else if (path.bestEffortTrafficCondition == BasePIB.Path.YELLOW)
    {
      int firstNodeID = path.getSrcNodeID();
			int lastNodeID = path.getDestNodeID();
			BasePIB.Path primaryPath = primaryPathForThisNodePair(firstNodeID, lastNodeID);
			BasePIB.Path alternatePath = alternatePathForThisNodePair(firstNodeID, lastNodeID);
			if (path == primaryPath)
			{
				if (alternatePath.bestEffortTrafficCondition == BasePIB.Path.GREEN)
				{
		      IPv6Address routerID = (IPv6Address) myBasePIB.htNodeIDtoRouterID.get(new Integer(firstNodeID));
		      myServer.sendCongestionAdvisory(routerID, path.getPathID().intValue(), CongestionAdvisory.GREEN);
		      path.congestionCleared();
					gui.sendText("\nCongestion cleared on path " + path.getPathID().intValue() + ".");
					gui.sendText("No more congestion for node pair (" + firstNodeID + "," + lastNodeID + ").");
					gui.sendText("Congestion Advisory GREEN sent to node " + firstNodeID + ".");
				}
				else
				{
					gui.sendText("Congestion has cleared on a primary path " + path.getPathID() + " while alternate path lossy.");
					path.congestionCleared();
					switchback(firstNodeID, lastNodeID);
				}
			}
			else
			{
				path.congestionCleared();
			}
    }
  }//end reactiveMonitor()


  /**
   * Proactive monitoring takes place during global congestion.
   * @param   path      the path being observed
   * @param   lossRate  the best effort loss rate on that path
   */
  protected void proactiveMonitor(BasePIB.Path path, short lossRate)
  {
    path.bestEffortLossRate = lossRate;//only recorded during active monitoring
		
    long currentTime = System.currentTimeMillis();
    if (lossRate > myBasePIB.thresholdLossRate)
    {
      timeLastCongestion = currentTime;
    }
    else if ((currentTime - timeLastCongestion) > (localResolutionTimeout * myBasePIB.timeScale))
    {
      terminateGlobalCongestionResolution();
      reactiveMonitor(path, lossRate);
			gui.sendText("\nGlobal congestion resolved!");
			gui.sendText("Terminating global coneston resolution procedures.");
      return;
    }
  
    if ((currentTime - timeLastActionTaken) > (localResolutionTimeout * myBasePIB.timeScale))
    {
			gui.sendText("\nAttempting to rob from the rich and give to the poor.");
			gui.sendText("Calculating fairness variables...");
			calculateFairnessVariables();
			boolean robbed = robFromTheRich();
			boolean gave = giveToThePoor();
      if (robbed || gave)
      {
        timeLastActionTaken = currentTime;
      }
			else
			{
				timeLastActionTaken = currentTime + (10 * localResolutionTimeout * myBasePIB.timeScale);
				gui.sendText("No action taken.");
			}
    }
    else
    {
      reactiveMonitor(path, lossRate);
    }
  }
  //end proactiveMonitor()

  /**
   * Every time a new edge router is discovered, the BE topology is updated
   * and new paths are deployed as necessary.
   */
  protected void updateBEtopology()
  {
	
    //first, reset the topology
		gui.sendText("Resetting old paths...");
    Enumeration allpaths = myBasePIB.htPaths.elements();
    while (allpaths.hasMoreElements())
    {
      BasePIB.Path thispath = (BasePIB.Path) (allpaths.nextElement());
      if (thispath.bBestEffortTraffic)
      {
        thispath.terminateBestEffortTraffic();
      }
    }
		gui.sendText("reset.");
  
		beNodePairAdmin(DEPLOY_INITIAL_PATHS);
		
  }//end updateBEtopology()

  /**
   * When one of a BET agent's path fails, the BEM restores redundancy by
   * deploying a new path.
   * @param   pathID  the remaining path 
   */
  private void restoreRedundancy(int pathID)
  {
    BasePIB.Path deadPath = (BasePIB.Path) (myBasePIB.htPaths.get(new Integer(pathID)));
    BasePIB.Path livePath = null;
    int srcNodeID = ((BasePIB.Path) (myBasePIB.htPaths.get(new Integer(pathID)))).getSrcNodeID();
    int destNodeID = ((BasePIB.Path) (myBasePIB.htPaths.get(new Integer(pathID)))).getDestNodeID();
		gui.sendText("Affected node pair is (" + srcNodeID + "," + destNodeID + ").");
    IPv6Address srcRouterID = (IPv6Address) (myBasePIB.htNodeIDtoRouterID.get(new Integer(srcNodeID)));
    IPv6Address destRouterID = (IPv6Address) (myBasePIB.htNodeIDtoRouterID.get(new Integer(destNodeID)));
    
    //first, determine the identity of the live path
    Vector bePaths = getThisNodePairsBEpaths(srcNodeID, destNodeID);
    Enumeration thesePaths = bePaths.elements();
    while (thesePaths.hasMoreElements())
    {
      BasePIB.Path thisPath = (BasePIB.Path) (thesePaths.nextElement());
      if (deadPath != thisPath)
      {
        livePath = thisPath;
      }
    }
    
    //if this was the only path, then nothing can be done
    if (livePath == null)
    {
			gui.sendText("NO SURVIVING PATH!!!");
      return;
    }

		gui.sendText("Resent surviving path " + livePath.getPathID() + " to reset the destination.");

    BasePIB.Path newRedundantPath = myBasePIB.routingAlgorithm.findPath(srcRouterID,
                                                                        destRouterID,
                                                                        livePath,    
                                                                        myBasePIB.routingAlgorithm.SHORTEST_WIDEST_MOST_DISJOINT_PATH);
    //now, attempt to find and send a new alternate path
    if (newRedundantPath != null)
    {
      Integer newRedundantPathID = newRedundantPath.getPathID();
      if (!newRedundantPath.bCreated)
      {
        myBasePIB.setupPath(newRedundantPath, newRedundantPathID.intValue(), FlowRoutingTableEntry.INSTALLED_FOR_BE);
        newRedundantPath.bCreated = true;
      }
			gui.sendText("Sending path " + newRedundantPathID + " as the new alternate path.");
			gui.sendText("Redundancy restored!");
    }
		else//resend the same path as alternate
		{
			newRedundantPath = livePath;
			gui.sendText("Unable to find a redundant path.  Resending the primary path as alternate.");
		}//end if else
		
		sendTableEntries(srcRouterID, destRouterID, livePath.getPathID().intValue(), newRedundantPath.getPathID().intValue());
		
  }//end restoreRedundancy()

  /**
   * Tests to see if there are two BE routes currently active for this pair.
   * @param   srcNodeID    the source node  
   * @param   destNodeID  the destination node
   * @return  whether there are
   */
  private boolean twoBEroutesActive(int srcNodeID, int destNodeID)
  {
    if (getThisNodePairsBEpaths(srcNodeID, destNodeID).size() == 2)  
    {
      return true;
    }
    else
    {
      return false;
    }
  }

  /**
   * Expires best effort paths for this node pair.
   * @param   srcNodeID  
   * @param   destNodeID  
   */
  private void expireBEpaths(int srcNodeID, int destNodeID)
  {
    BasePIB.Path thisPath;
    
    Enumeration thesePaths = getThisNodePairsBEpaths(srcNodeID, destNodeID).elements();
    while (thesePaths.hasMoreElements())  
    {
      thisPath = (BasePIB.Path) (thesePaths.nextElement());
      if ((thisPath.getSrcNodeID() == srcNodeID) && (thisPath.getDestNodeID() == destNodeID))  
      {
        thisPath.expireBEpath();
      }
    }
  }
  
  /**
   * Unexpire those BE paths that have been expired
   * past the required time.
   */
  private void unexpireBEpaths()
  {
    bePathAdmin(0, 0, UNEXPIRE_PATHS);
  }

  /**
   * Determines the best effort paths for this node pair.
   * @param   srcNodeID  
   * @param   destNodeID  
   * @return  best effort paths as a Vector
   */
  private Vector getThisNodePairsBEpaths(int srcNodeID, int destNodeID)
  {
    return bePathAdmin(srcNodeID, destNodeID, GET_PATHS);
  }
  
  /**
   * Determines if global congestion is occurring.
   * @return whether it's occurring
   */
  protected boolean globalCongestionIsOccurring()
  {
    return globalCongestion;
  }
  
  /**
   * Initiates global congestion resolution procedures.
   */
  private void initiateGlobalCongestionResolution()
  {  
    globalCongestion = true;
    
    BasePIB.Path thisPath;
    BasePIB.PathQoS thisqos;//must be declared here for visibility purposes
    
		//update loss rate parameters for all BE paths
    bePathAdmin(0, 0, UPDATE_LOSS_RATE);
    
		gui.sendText("Calculating fairness variables...");
    calculateFairnessVariables();
    
    timeLastActionTaken = System.currentTimeMillis();
		timeLastCongestion = System.currentTimeMillis();
  }

  /**
   * Calculates fairness variables to base later
   * actions upon.
   */
  private void calculateFairnessVariables()
  {
    Vector bepaths = new Vector();
    Vector samples = new Vector();
    IPv6Address thisRouterID;
    int srcNodeID;
    int destNodeID;
    BasePIB.Path thisbepath;
    BasePIB.Path thisPath;
    int count = 0;
    
		samples = (Vector) (beNodePairAdmin(GET_LOSS_RATES));
    
    meanLossRate = computeMean(samples);
		gui.sendText("Mean loss rate is " + (meanLossRate/100) + "%.");
    stdLossRateDev = computeStdDev(samples);
		gui.sendText("Loss rate SD is " + (stdLossRateDev/100) + "%.");    
  }

  /**
   * Computes loss rate from this node pair assuming all traffic is on alternate path.
   * @param   srcNodeID  
   * @param   destNodeID  
   * @return  best effort loss rate     
   */
  private short lossRateFromThisNodePair(int srcNodeID, int destNodeID)
  {
    long timeDeployed = 0;
    short lossRate = 0;
    BasePIB.Path thisPath;
    Vector bepaths = getThisNodePairsBEpaths(srcNodeID, destNodeID);
		
		if (srcNodeID == destNodeID)
		{
			return 0;
		}
    
    Enumeration thesepaths = bepaths.elements();
    while (thesepaths.hasMoreElements())
    {
      thisPath = (BasePIB.Path) thesepaths.nextElement();
      if (thisPath.bBestEffortTraffic && (thisPath.timeBEinitiated > timeDeployed))    
      {
        timeDeployed = thisPath.timeBEinitiated;
        lossRate = thisPath.bestEffortLossRate;
      }
    }
    return lossRate;
  }          
  
  /**
   * Terminates global congestion and proactive monitoring.
   */
  private void terminateGlobalCongestionResolution()
  {
    globalCongestion = false;
  }
  

  /**
   * Fairness measure that will release resources from those pairs
   * not experiencing congestion.
   * @return  success of operation     
   */
  private boolean robFromTheRich()
  {

    return ((Boolean) (beNodePairAdmin(ROB_IF_RICH))).booleanValue();

  }//end robFromTheRich()
  
  /**
   * Fairness measure that will give more resources to those pairs
   * experiencing undue congestion
   * @return  success of operation     
   */
  private boolean giveToThePoor()
  { 
	   
    reclaimExpiredPaths();
    
		return ((Boolean) (beNodePairAdmin(GIVE_IF_POOR))).booleanValue();
		       
  }//end giveToThePoor()

  /**
   * Reclaims expired paths that have no congestion for reuse.
   */
  private void reclaimExpiredPaths()
  {
    BasePIB.Path thisPath = null;
    BasePIB.PathQoS thisPathQoS;
    
    Enumeration allPaths = myBasePIB.htPaths.elements();
    while (allPaths.hasMoreElements())
    {
      thisPath = (BasePIB.Path) (allPaths.nextElement());
      if (thisPath.bestEffortTrafficCondition == BasePIB.Path.RED)
      {
        if (thisPath.bestEffortLossRate < myBasePIB.thresholdLossRate)
        {
          thisPath.unexpireBEpath();
        }
      }
    }
  }

  /**
   * Resets traffic for a node pair back to the primary path.
   * @param   srcNodeID  
   * @param   destNodeID  
   */
  private boolean switchback(int srcNodeID, int destNodeID)
  {
		if ((System.currentTimeMillis() - timeLastSwitchback) < (myServer.getAC_cyclePeriod()))
		{
			return false;
		}
	
    BasePIB.Path primaryPath = primaryPathForThisNodePair(srcNodeID, destNodeID);
    BasePIB.Path alternatePath = primaryPathForThisNodePair(srcNodeID, destNodeID);
    
    IPv6Address srcRouterID = (IPv6Address) (myBasePIB.htNodeIDtoRouterID.get(new Integer(srcNodeID)));
    IPv6Address destRouterID = (IPv6Address) (myBasePIB.htNodeIDtoRouterID.get(new Integer(destNodeID)));
		
		sendTableEntries(srcRouterID, destRouterID, primaryPath.getPathID().intValue(), alternatePath.getPathID().intValue());
    
    myServer.sendCongestionAdvisory(srcRouterID, primaryPath.getPathID().intValue(), CongestionAdvisory.GREEN);
		gui.sendText("Reset traffic split to 100/0 for node pair (" + srcNodeID + "," + destNodeID + ").");
		gui.sendText("Congestion Advisory GREEN sent to node " + srcNodeID + ".");
		
		timeLastSwitchback = System.currentTimeMillis();
		return true;
  }

  /**
   * Determines the primary path for this node pair based on deployment time.
   * @param   srcNodeID  
   * @param   destNodeID  
   * @return  primary path
   */
  private BasePIB.Path primaryPathForThisNodePair(int srcNodeID, int destNodeID)
  {
    BasePIB.Path thisPath;
    BasePIB.Path primaryPath = null;
    long leastRecentTime = System.currentTimeMillis();
    
    Vector bepaths = getThisNodePairsBEpaths(srcNodeID, destNodeID);
    
    Enumeration enum = bepaths.elements();
    while (enum.hasMoreElements())
    {
      thisPath = (BasePIB.Path) (enum.nextElement());
      if (thisPath.timeBEinitiated < leastRecentTime)
      {
        leastRecentTime = thisPath.timeBEinitiated;
        primaryPath = thisPath;
      }
    }
    
    return primaryPath;
  }

  /**
   * Determines the alternate path for this node pair based on deployment time.
   * @param   srcNodeID  
   * @param   destNodeID  
   * @return  alternate path     
   */
  private BasePIB.Path alternatePathForThisNodePair(int srcNodeID, int destNodeID)
  {
    BasePIB.Path thisPath;
    BasePIB.Path alternatePath = null;
    long mostRecentTime = 0;
    
    if (!twoBEroutesActive(srcNodeID, destNodeID))
    {
      return null;
    }
    
    Vector bepaths = getThisNodePairsBEpaths(srcNodeID, destNodeID);
    
    Enumeration enum = bepaths.elements();
    while (enum.hasMoreElements())
    {
      thisPath = (BasePIB.Path) (enum.nextElement());
      if (thisPath.timeBEinitiated > mostRecentTime)
      {
        mostRecentTime = thisPath.timeBEinitiated;
        alternatePath = thisPath;
      }
    }
    
    return alternatePath;
  }
  
  /**
   * Computes the mean of a set of values.
   * @param   samples  the set of values (must cast to Integer)  
   * @return  mean     
   */
  private double computeMean(Vector samples)
  {
    int sum = 0;
    int count = 0;
    
    Enumeration enum = samples.elements();
    while (enum.hasMoreElements())
    {
      sum += ((Integer) (enum.nextElement())).intValue();
      count ++;
    }
    if (count == 0)
    {
      return 0;
    }
    else
    {
      return sum / count;
    }
  }

  /**
   * Computes the standard deviation of a set of values.
   * @param   samples  the set of values (must cast to Integer)  
   * @return  standard deviation     
   */
  private double computeStdDev(Vector samples)
  {
    int sum = 0;
    int thisElement = 0;
    int count = 0;
    double mean = computeMean(samples);
    
    Enumeration enum = samples.elements();
    while (enum.hasMoreElements())
    {
      thisElement = ((Integer) (enum.nextElement())).intValue();
      sum += (thisElement - mean) * (thisElement - mean);
      count++;
    }
    if (count == 0)
    {
      return 0;
    }
    else
    {
      return java.lang.Math.sqrt((double) (sum / count));
    }
  }

  /**
   * Method through which a BE path failure notification is made.
   * @param   failedPathID  ID of the failed path
   */
  protected void handleBEpathFailure(int failedPathID)
  {
		gui.sendText("\nHandling failure of path " + failedPathID + ".");
		BasePIB.Path thisPath = (BasePIB.Path) (myBasePIB.htPaths.get(new Integer(failedPathID)));
		int srcNodeID = thisPath.getSrcNodeID();
		IPv6Address srcRouterID = (IPv6Address) (myBasePIB.htNodeIDtoRouterID.get(new Integer(srcNodeID)));
		myServer.sendCongestionAdvisory(srcRouterID, thisPath.getPathID().intValue(), CongestionAdvisory.RED);
		gui.sendText("Congestion Advisory RED sent to node " + srcNodeID + ".");
		gui.sendText("Attempting to restore redundancy...");
    restoreRedundancy(failedPathID);
  }


	/**
	 * Whenever BEM generates new paths for a BE node pair, this method is called
	 * to send the table entries and perform the bookkeeping.  Note that entries
	 * are always sent in pairs.  This is to force a 100/0 reset on the BET agent
	 * end and acceptance of these new entries as active.
	 * @param   srcRouterID			the source router ID  
	 * @param   destRouterID  	the destination router ID
	 * @param   primaryPathID  	the primary path ID
	 * @param   alternatePathID	the alternate path ID
	 */
	private void sendTableEntries(IPv6Address srcRouterID, IPv6Address destRouterID, int primaryPathID, int alternatePathID)
	{
		int destNodeID = ((Integer) (myBasePIB.htRouterIDtoNodeID.get(destRouterID.toString()))).intValue();

		try
		{
	    Enumeration interfaces = vBestEffortDestAdds.elements();
	    while (interfaces.hasMoreElements())    
	    {
	      IPv6Address thisInterfaceAdd = IPv6Address.getByName((String) interfaces.nextElement());
	      if (destNodeID == ((BasePIB.InterfaceInfo)  myBasePIB.htInterfaces.get(thisInterfaceAdd.toString())).getNodeID().intValue())
	      {
	        myServer.sendBETUpdate(srcRouterID, thisInterfaceAdd, primaryPathID, 0, 0);
					BasePIB.Path primaryPath = (BasePIB.Path) myBasePIB.htPaths.get(new Integer(primaryPathID));
					primaryPath.initiateBestEffortTraffic();
					primaryPath.timeBEinitiated -= 1;//other parts of code require primary path to be older
	        myServer.sendBETUpdate(srcRouterID, thisInterfaceAdd, alternatePathID, 0, 0);
	      	BasePIB.Path alternatePath = (BasePIB.Path) myBasePIB.htPaths.get(new Integer(alternatePathID));
					alternatePath.initiateBestEffortTraffic();
	        
				}
	    }
		}
		catch (UnknownHostException uhe)
		{
			System.out.println("UHE thrown by sendTableEntries() in BestEffortManager.");
		}
	}	

	/**
	 * All code requiring an all paths iterator is consolidate here.
	 * @param   srcNodeID  source node ID
	 * @param   destNodeID destination node ID
	 * @param   action  	 byte code defined at beginning of class
	 * @return     
	 */
	private Vector bePathAdmin(int srcNodeID, int destNodeID, byte action)
	{
		Vector bepaths = new Vector();

    BasePIB.PathQoS thisPathQoS;
	
		Enumeration allPaths = myBasePIB.htPaths.elements();
		
		while (allPaths.hasMoreElements())
		{
			BasePIB.Path thisPath = (BasePIB.Path) allPaths.nextElement();
			
			switch (action)
			{
				case UNEXPIRE_PATHS:
					if (thisPath.bestEffortTrafficCondition == BasePIB.Path.RED)
		      {
		        if ((System.currentTimeMillis() - thisPath.timeConditionRed) >
		            (PATH_EXPIRATION_TIME * myBasePIB.timeScale))
		        {
		          if (thisPath.unexpireBEpath())
				  		{
								gui.sendText("\nPath " + thisPath.getPathID() + " has been unexpired.");
				  		}
		        }
		      }
					break;
				
				case GET_PATHS:
					if ((thisPath.bBestEffortTraffic) && (thisPath.getSrcNodeID() == srcNodeID)
		          && (thisPath.getDestNodeID() == destNodeID))
		      {
		        bepaths.add(thisPath);
		      }
					break;
				
				case UPDATE_LOSS_RATE:
					if (thisPath.bBestEffortTraffic)
		      {
		        BasePIB.PathQoS thisqos = thisPath.getPathQoSArray()[BasePIB.BEST_EFFORT];
		        thisPath.bestEffortLossRate = thisqos.getPacketLossRate();
		      }
					break;
					
				case RECLAIM_PATHS:
		      if (thisPath.bestEffortTrafficCondition == BasePIB.Path.RED)
		      {
		        if (thisPath.bestEffortLossRate < myBasePIB.thresholdLossRate)
		        {
		          thisPath.unexpireBEpath();
		        }
		      }
					break;
				
				default:
					break;				
			}//end switch			
		}//end while
		
		return bepaths;
		
	}//end bePathAdmin()
	

	/**
	 * All code requiring node pair iterator is consolidate here.
	 * @param   action	byte code defined at beginning of class
	 * @return     
	 */
	private Object beNodePairAdmin(byte action)
	{
		boolean bResult = false;
		Vector vResult = new Vector();
		
		BasePIB.Path thisPath = null;
		BasePIB.Path pathToExpire, primaryPath, alternatePath, reclaimPath;
		BasePIB.PathQoS thisPathQoS;
		
		try
		{
	    Enumeration eSources = vBestEffortRouters.elements();    
	    while (eSources.hasMoreElements())
	    {
				IPv6Address srcRouterID = IPv6Address.getByName((String) eSources.nextElement());
				Integer srcNodeID = ((BasePIB.InterfaceInfo) myBasePIB.htInterfaces.get(srcRouterID.toString())).getNodeID();
				Enumeration eDestinations = vBestEffortRouters.elements();
	      while (eDestinations.hasMoreElements())
	      {
	        IPv6Address interfaceAddress = IPv6Address.getByName((String) eDestinations.nextElement());
	        Integer destNodeID = ((BasePIB.InterfaceInfo) myBasePIB.htInterfaces.get(interfaceAddress.toString())).getNodeID();
	        IPv6Address destRouterID = (IPv6Address) myBasePIB.htNodeIDtoRouterID.get(destNodeID);
					
					switch (action)
					{
						case DEPLOY_INITIAL_PATHS:
			        //SHORTEST WIDEST PATH is used for the primary path
			        BasePIB.Path bePath1 = myBasePIB.routingAlgorithm.findPath(srcRouterID,
			                                                                   destRouterID,
			                                                                   null,
			                                                                   myBasePIB.routingAlgorithm.SHORTEST_WIDEST_PATH);                  
			        if (bePath1 != null)
			        {
			          Integer bePathID1 = bePath1.getPathID();
			          if (!bePath1.bCreated)
			          {
			            myBasePIB.setupPath(bePath1, bePathID1.intValue(), FlowRoutingTableEntry.INSTALLED_FOR_BE);
			            bePath1.bCreated = true;
			          }
								gui.sendText("Path " + bePathID1.intValue() + " deployed as primary for (" + srcNodeID.intValue() + "," + destNodeID.intValue() + ").");
			          //SHORTEST WIDEST MOST DISJOINT PATH  is used for the alternate path
			          BasePIB.Path bePath2 = myBasePIB.routingAlgorithm.findPath(srcRouterID,
			                                                                     destRouterID,
			                                                                     bePath1,
			                                                                     myBasePIB.routingAlgorithm.SHORTEST_WIDEST_MOST_DISJOINT_PATH);
			          if (bePath2 != null)
			          {
			            Integer bePathID2 = bePath2.getPathID();
			            if (!bePath2.bCreated)
			            {
			              myBasePIB.setupPath(bePath2, bePathID2.intValue(), FlowRoutingTableEntry.INSTALLED_FOR_BE);
			              bePath2.bCreated = true;
			            }
									gui.sendText("Path " + bePathID2.intValue() + " deployed as alternate.");
			          }
			          else
			          { 
			            bePath2 = bePath1;
									gui.sendText("No alternate path available.");
			          }
								sendTableEntries(srcRouterID, destRouterID, bePath1.getPathID().intValue(), bePath2.getPathID().intValue());             
			        }//end if
							break;
							
						case GET_LOSS_RATES:
							if (srcNodeID != destNodeID)
							{
								vResult.add(new Integer((int) (lossRateFromThisNodePair(srcNodeID.intValue(), destNodeID.intValue()))));
							}
							break;
						
						case ROB_IF_RICH:
							int leastBandwidth, thisBandwidth;
							if ((lossRateFromThisNodePair(srcNodeID.intValue(), destNodeID.intValue()) < (meanLossRate - stdLossRateDev)) &&
			            twoBEroutesActive(srcNodeID.intValue(), destNodeID.intValue()))			        
							{
			          leastBandwidth = 2000000000;//a large number
			          Vector bepaths = getThisNodePairsBEpaths(srcNodeID.intValue(), destNodeID.intValue());
			          Enumeration enum = bepaths.elements();
			          while (enum.hasMoreElements())
			          {
			            thisPath = (BasePIB.Path) (enum.nextElement());
			            thisPathQoS = thisPath.getPathQoSArray()[BasePIB.BEST_EFFORT];
			            thisBandwidth = thisPathQoS.getAvailableBandwidth();
			            if (thisBandwidth < leastBandwidth)
			            {
			              leastBandwidth = thisBandwidth;
			              pathToExpire = thisPath;
			            }
			          }
			          srcRouterID = (IPv6Address) (myBasePIB.htNodeIDtoRouterID.get(srcNodeID));
			          myServer.sendCongestionAdvisory(srcRouterID, thisPath.getPathID().intValue(), CongestionAdvisory.RED);
								thisPath.expireBEpath();
			          bResult = true;
								gui.sendText("Deactivated path " + thisPath.getPathID() + " for node pair (" + srcNodeID + "," + destNodeID + ").");
								gui.sendText("Robbed from the rich.");
			        }//end if
							break;
						
						case GIVE_IF_POOR:
							int currentBandwidth, switchbackBandwidth, reclaimableBandwidth;
							if ((lossRateFromThisNodePair(srcNodeID.intValue(), destNodeID.intValue()) > (meanLossRate + stdLossRateDev)) &&
			            twoBEroutesActive(srcNodeID.intValue(), destNodeID.intValue()))
							{
			          primaryPath = primaryPathForThisNodePair(srcNodeID.intValue(), destNodeID.intValue());
			          alternatePath = alternatePathForThisNodePair(srcNodeID.intValue(), destNodeID.intValue());
			          srcRouterID = (IPv6Address) (myBasePIB.htNodeIDtoRouterID.get(srcNodeID));
			          destRouterID = (IPv6Address) (myBasePIB.htNodeIDtoRouterID.get(destNodeID));
			          reclaimPath = myBasePIB.routingAlgorithm.findPath(srcRouterID,
			                                                            destRouterID,
			                                                            null,
			                                                            myBasePIB.routingAlgorithm.SHORTEST_WIDEST_PATH);
			          if (twoBEroutesActive(srcNodeID.intValue(), destNodeID.intValue()))
			          {
			            thisPathQoS = primaryPath.getPathQoSArray()[BasePIB.BEST_EFFORT];
			            switchbackBandwidth = thisPathQoS.getAvailableBandwidth();
			            thisPathQoS = alternatePath.getPathQoSArray()[BasePIB.BEST_EFFORT];
			            currentBandwidth = thisPathQoS.getAvailableBandwidth();
			          }
			          else
			          {
			            thisPathQoS = primaryPath.getPathQoSArray()[BasePIB.BEST_EFFORT];
			            currentBandwidth = thisPathQoS.getAvailableBandwidth();
			            switchbackBandwidth = 0;
			          }
			          if (reclaimPath != null)
			          {
			            thisPathQoS = reclaimPath.getPathQoSArray()[BasePIB.BEST_EFFORT];
			            reclaimableBandwidth = thisPathQoS.getAvailableBandwidth();
			          }
			          else
			          {
			            reclaimableBandwidth = 0;			
			          }
			          if ((switchbackBandwidth > currentBandwidth) && (switchbackBandwidth >= reclaimableBandwidth))
			          {
			            bResult = switchback(srcNodeID.intValue(), destNodeID.intValue());
									gui.sendText("Gave to the poor.");
			          }
			          else if (reclaimableBandwidth > currentBandwidth)
			          {
			            Vector bepaths = getThisNodePairsBEpaths(srcNodeID.intValue(), destNodeID.intValue());
			            Enumeration enum = bepaths.elements();
			            while (enum.hasMoreElements())
			            {
			              thisPath = (BasePIB.Path) (enum.nextElement());
			              thisPath.terminateBestEffortTraffic();
			            }
									
			            myServer.sendCongestionAdvisory(srcRouterID, reclaimPath.getPathID().intValue(), CongestionAdvisory.GREEN);
									gui.sendText("Deployed fatter path " + reclaimPath + " for node pair (" + srcNodeID + "," + destNodeID + ").");
			            BasePIB.Path bePath2 = myBasePIB.routingAlgorithm.findPath(srcRouterID,
			                                                                       destRouterID,
			                                                                       reclaimPath,
			                                                                       myBasePIB.routingAlgorithm.SHORTEST_WIDEST_MOST_DISJOINT_PATH);
			            if (bePath2 != null)
			            {
			              Integer bePathID2 = bePath2.getPathID();
			              if (!bePath2.bCreated)
			              {
			                myBasePIB.setupPath(bePath2, bePathID2.intValue(), FlowRoutingTableEntry.INSTALLED_FOR_BE);
			                bePath2.bCreated = true;
			              }
										gui.sendText("Deployed new alternate path " + bePathID2 + " for node pair (" + srcNodeID + "," + destNodeID + ").");
			            }
			            else
			            {
										bePath2 = reclaimPath;
			            }//end if
									sendTableEntries(srcRouterID, destRouterID, reclaimPath.getPathID().intValue(), bePath2.getPathID().intValue());
			            bResult = true;
									gui.sendText("Gave to the poor.");
			          }//end if
			        }//end if
							break;
						
		        default:
							break;
					}//end switch
	      }//end while
	    }//end while
		}
		catch (UnknownHostException uhe)
		{
			System.out.println("UHE thrown by beNodePairAdmin() in BestEffortManager.");
		}
		
		switch (action)
		{
		case GET_LOSS_RATES:
			return vResult;
			
		case ROB_IF_RICH:
			return new Boolean(bResult);
			
		case GIVE_IF_POOR:
			return new Boolean(bResult);
			
		default:
			return null;
		}
		
	}//end beNodePairAdmin()

}