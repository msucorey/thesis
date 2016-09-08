//25Feb02[Wofford] - Sudafed renamed Congestion Advisory.
//31Jan02[Wu] - repackaged
//09Oct01[Wofford]  - created

//[cw] need to comment this bad boy!

package org.saamnet.saam.agent.router;

import java.util.*;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.saamnet.saam.control.*;
import org.saamnet.saam.agent.*;
import org.saamnet.saam.router.*;
import org.saamnet.saam.net.*;
import org.saamnet.saam.event.*;
import org.saamnet.saam.message.*;
import org.saamnet.saam.gui.*;
//import com.objectspace.jgl.HashMap;//[cw]may be overkill for this class

/**
 * The BestEffortTable is a lookup table the router uses to associate
 * unlabeled BE traffic destined for a particular address to a path
 * that is installed in the FlowRoutingTable.  It is "smarter" than a
 * FlowRoutingTable, though, in that it will actually make decisions
 * independent of examining a single entry.
 */
public class BestEffortTable extends Hashtable implements TableResidentAgent,
		MessageProcessor
{

  //the maximum number of routes to split to a single destination 
  public final static int MAX_ROUTES = 2;
	
  private TableGui gui;
  
  private Vector columnLabels = new Vector();

  private ControlExecutive controlExec;
 
  private byte[] myMessages = //-crcp generic message registration
  {
    Message.BEST_EFFORT_TBL_ENTRY,
    Message.CONGESTION_ADVISORY
  };

	//hashtable of TrafficDestination objects keyed by destination IP address
  private Hashtable destinationList = new Hashtable();
	
	//The BestEffortTable acts autonomously at various intervals depending
	//on most recent information in Congeston Advisory messages for a server.  If a
	//TrafficDestination is congested, it will "redirect" traffic
	//periodically to an alternate route.  If the congestion has cleared,
	//then it will gradually revert until all traffic is carried by the
	//primary route.  These constants allow for tuning performance.
	//Philosophically, when you have congestion, you want to act QUICKLY.
	//When the congestion clears, gradually revert to the primary route.
	//The primary route is often more desirable AND it's best to have
	//maximum room available on the alternate route to handles extra traffic.
	private int timeScale;
  private final static int REDIRECT_INTERVAL = 200;//should always be equal to AC_Cycle time
  private final static int REVERT_INTERVAL = 1800000;//30 minutes
    
  /**
   * Constructs a BestEffortTable.
   */
  public BestEffortTable()
  {
    //super(true);
  }
	
	/**
   * A TrafficDestination is a data structure used by the BestEffortTable to
	 * track information on a per-destination basis.  Notably, it holds two
	 * key arrays.  Array currentSplit holds the split in percentage over the
	 * number of routes being used.  Array routeInstalled tracks whether those
	 * routes have been received from the server and installed by BestEffortTable.
	 * It is twice as big in order to hold a full complement of spare routes.
   */
	public static class TrafficDestination
	{
		public IPv6Address destination;
    public int[] currentSplit = new int[MAX_ROUTES];//the traffic split
  	public boolean[] routeInstalled = new boolean[2 * MAX_ROUTES];
  	public int primaryRoute;//array index pointer for primary route
  	public int nextEntry;//array index pointer for where to install next route

		public byte trafficCondition;
		public boolean isUsingAlternateRoute;
  	public long timeLastRedirect;
		public long timeLastRevert;
  
  	public TrafficDestination(IPv6Address address)
  	{
  		destination = address;
    	primaryRoute = 0;
    	nextEntry = 0;
    	currentSplit[primaryRoute] = 100;
    	trafficCondition = CongestionAdvisory.GREEN;
			isUsingAlternateRoute = false;
    	timeLastRedirect = 0;
  	}
	}

  /**
   * Required install method of the ResidentAgent interface.
   *
   * @param controlExec The ControlExecutive on the router this agent
   *        is being installed on.
   * @param String instanceName
   * @param String [] parameters - array of parameters for this agent
   */
  public void install(ControlExecutive controlExec,
                      String instanceName,
                      String [] parameters)
  {  
    columnLabels.add("Dest IPv6 Address");
    columnLabels.add("Map to Path");    
    columnLabels.add("Traffic Split");
    int[] columnWidths = {210, 120, 120};
    gui = new TableGui(controlExec.mainGui.getContentPanel(), instanceName, columnLabels, columnWidths);
    controlExec.addTableGui(gui); //-crcy    
    this.controlExec = controlExec;
    controlExec.registerMessageProcessor(myMessages, this);
		timeScale = controlExec.getTimeScale();
  }
  
  
  /**
   * Required uninstall method of the ResidentAgent interface.
   */
  public void uninstall(){
    clear();
  }

  /**
   * The communication method through which ResidentAgent talks.
   * @param   message  a BETE with only the destination field
   * @return  a BETE with a path ID filled in to map to
   */
  public Message query (Message message)
  {
    IPv6Address destAddr = ((BestEffortTableEntry) message).getDestAddr();
    int bucketMap = ((BestEffortTableEntry) message).getSplit();
    BestEffortTableEntry result = getBestEffortTableEntry(destAddr, bucketMap);
    return result;
  }
  
  /**
   * Retrieves the BET entry for a destination address and bucket map.
   * @param   destAddr  
   * @param   bucketMap  
   * @return  the associated BETE     
   */
  public BestEffortTableEntry getBestEffortTableEntry(IPv6Address destAddr, int bucketMap)
  {
    TrafficDestination trafDest = (TrafficDestination) destinationList.get(destAddr.toString());
    //if BE traffic is congested to this destination, redirect traffic to alternate path
    if (trafDest != null) //may be no such entry yet; see RoutingAlogrithm
		{
			if ((trafDest.trafficCondition == CongestionAdvisory.YELLOW) &&
    		  ((System.currentTimeMillis() - trafDest.timeLastRedirect) > 
					 (REDIRECT_INTERVAL * timeScale)))
    	{
    		redirect(trafDest);
    	}
			if ((trafDest.isUsingAlternateRoute) && (trafDest.trafficCondition == CongestionAdvisory.GREEN) &&
			    ((System.currentTimeMillis() - trafDest.timeLastRevert) >
					 (REVERT_INTERVAL * timeScale)))
			{
				revert(trafDest);
			}

    	int serialNo = trafDest.primaryRoute;
    	int split = 0;//0%
    	int percentile = bucketMap * 10 + 10;//i.e. f(0)=10%...f(9)=100%
    
    	for (int counter = 0; counter < MAX_ROUTES; counter++)
    	{
    		split += trafDest.currentSplit[counter];
      	if (percentile <= split)
      	{
      		serialNo = (serialNo + counter) % (MAX_ROUTES * 2);
      		break;
      	}
    	}
      
      String key = destAddr.toString() + serialNo;
      BestEffortTableEntry result = (BestEffortTableEntry) get(key);
      return result;//expect null if no entry; see RoutingAlgorithm
		}
		else
		{
			return null;
		}
  }
  
  /**
  * Returns true if the BET contains an entry indexed by destination 
  * address andfalse otherwise.
  * @param destAddr, a particular destination IP address
  * @return whether or not the BET contains an entry indexed by the destination address
  */  
  public boolean hasEntry(IPv6Address destAddr)
  {
    if (destinationList.get(destAddr.toString()) != null)
	  {
		  return true;
	  }
	  else
	  {
		  return false;
	  }		
  }
  
  /**
  * Returns the entire contents of this BestEffortTable or null
  * if this BestEffortTable is empty.
  * @return A Vector of all entries currently
  *         in the flow routing table.
  */
  public Vector getTable()
  {
  
    if (isEmpty())  
    {
      return null;
    }
      
    Vector table = new Vector(size());      
    Enumeration e = elements();
    while (e.hasMoreElements())
    {
      Vector oneRow = new Vector();
      BestEffortTableEntry betentry = (BestEffortTableEntry) e.nextElement();
      
      oneRow.add("" + betentry.getDestAddr());
      oneRow.add("" + betentry.getPathMap());
      oneRow.add("" + betentry.getSplit());
      
      table.add(oneRow);
    }

    return table;

  }//End getTable()
  
  /**
   * Required method for ResidentAgents for state transfer
   * @param   replacement the ResidentAgent replacement 
   */
  public void transferState (ResidentAgent replacement)
  {
    for (Enumeration e = elements(); e.hasMoreElements();)
    {
       replacement.receiveState((BestEffortTableEntry) e.nextElement());
    }
  }
  
  
  /**
   * Required method for ResidentAgents to receive state.
   * @param   message a BETE (one at a time from transferState())
   */
  public void receiveState (Message message){
    add((BestEffortTableEntry) message);
  }

	/**
	 * BestEffortTable process two types of messages, BEST_EFFORT_TBL_ENTRY and CONGESTION_ADVISORY.
	 * For BEST_EFFORT_TBL_ENTRY, it adds the entry and makes a new TrafficDestination
   * if it does not have this destination on file.  For CONGESTION_ADVISORY, it updates the
   * congestion condition for the TrafficDestination using that pathID.
   * @param   message  CongestionAdvisory from BestEffortManager on server  
	 */
  public void processMessage (Message message)
  {
  	switch (message.getBytes()[0])
    {
	  	case Message.BEST_EFFORT_TBL_ENTRY:
		    BestEffortTableEntry betentry = null; //-crcp
		    try  //-crcp
		    {
		      betentry = new BestEffortTableEntry(message.getBytes()); //-crcp generic way
		    }
		    catch(UnknownHostException uhe)
		    {
		      System.out.println("BestEffortTable Error: can't create local BETE." + uhe);
		    }
        //check to see if this is a known destination
		    if (destinationList.containsKey(betentry.getDestAddr().toString()))
		    {
		    	TrafficDestination trafDest = (TrafficDestination) destinationList.get(betentry.getDestAddr().toString());
		      betentry.serialNo = trafDest.nextEntry;
          //check to see if a new complement of routes is being received
          //if so, reset previous splits to 0 and mark next route as primary
					boolean resetRoutes = (trafDest.nextEntry - trafDest.primaryRoute == MAX_ROUTES)
				                      	|| (trafDest.primaryRoute - trafDest.nextEntry == MAX_ROUTES);
					if (resetRoutes)
					{
						for (int i = 0; i < MAX_ROUTES; i++)
						{
							int index = (trafDest.primaryRoute + i) % (2 * MAX_ROUTES);
							String key = betentry.getDestAddr().toString() + index;
							BestEffortTableEntry zeroedentry = (BestEffortTableEntry) get(key);
							zeroedentry.split = 0;
							trafDest.currentSplit[i] = 0;
						}
						betentry.split = 100;
						trafDest.currentSplit[0] = 100;
						trafDest.primaryRoute = (trafDest.primaryRoute + MAX_ROUTES) % (2 * MAX_ROUTES);
						trafDest.isUsingAlternateRoute = false;						
					}
					else //this is not a new primary route
					{
		      	betentry.split = 0;
					}
		      add(betentry);
		      trafDest.routeInstalled[trafDest.nextEntry] = true;
		      trafDest.nextEntry = (trafDest.nextEntry + 1) % (2 * MAX_ROUTES);
				}
		    else //need to start tracking this new destination
		    {
		    	TrafficDestination trafDest = new TrafficDestination(betentry.getDestAddr());
		      destinationList.put(betentry.getDestAddr().toString(), trafDest);
		      betentry.serialNo = trafDest.nextEntry;
		      betentry.split = 100;
		      add(betentry);
		      trafDest.routeInstalled[trafDest.nextEntry] = true;
		      trafDest.nextEntry = (trafDest.nextEntry + 1) % (2 * MAX_ROUTES);
		    }
        //this is the server's way of granting edge router permission
		    controlExec.acceptEdgeTraffic();
	      break;
      
  		case Message.CONGESTION_ADVISORY:
        CongestionAdvisory pill = new CongestionAdvisory(message.getBytes());
        //determine affected path
        int affectedPathID = pill.getPathID();      
		    Enumeration e = elements();
		    while (e.hasMoreElements())
		    {
		      BestEffortTableEntry betentry1 = (BestEffortTableEntry) e.nextElement();
          if (betentry1.getPathMap() == affectedPathID)
          {
          	TrafficDestination trafDest = (TrafficDestination)
                                          destinationList.get(betentry1.getDestAddr().toString());
            //update the traffic condition
            trafDest.trafficCondition = pill.pathCondition();
            //if RED, then route all traffic to unaffected path
						if (pill.pathCondition() == CongestionAdvisory.RED)
						{
							int unaffectedSerialNo;
							int serialNo = betentry1.getSerialNo();
							if (serialNo == trafDest.primaryRoute)
							{
								unaffectedSerialNo = (serialNo + 1) % (2 * MAX_ROUTES);
							}
							else
							{
								unaffectedSerialNo = (serialNo - 1) % (2 * MAX_ROUTES);
							}
							String unaffectedKey = trafDest.destination.toString() + unaffectedSerialNo;
							BestEffortTableEntry unaffectedEntry = (BestEffortTableEntry) get(unaffectedKey);
							betentry1.setPathMap(unaffectedEntry.getPathMap());
							gui.fillTable(getTable());
						}
          }  
		    }
        break;
                
  		default:
      	break;
    }
         
    
  }//End processMessage()
 
  /**
   * Redirects one bucket of traffic from the primary to alternate path.
   * @param   trafDest the traffic destination
   * @return  success of operation
   */
  private boolean redirect(TrafficDestination trafDest)
  {
		int primaryRoute = trafDest.primaryRoute;
		int alternateRoute = (trafDest.primaryRoute + 1) % (2 * MAX_ROUTES);
		
		if ((trafDest.currentSplit[0] >= 10) && (trafDest.routeInstalled[alternateRoute]))
		{
			String primaryKey = trafDest.destination.toString() + primaryRoute;
    	BestEffortTableEntry primaryEntry = (BestEffortTableEntry) get(primaryKey);
			primaryEntry.setSplit(primaryEntry.getSplit() - 10);
			trafDest.currentSplit[0] -= 10;
			
			String alternateKey = trafDest.destination.toString() + alternateRoute;
			BestEffortTableEntry alternateEntry = (BestEffortTableEntry) get(alternateKey);
			alternateEntry.setSplit(alternateEntry.getSplit() + 10);
			trafDest.currentSplit[1] += 10;
			
			gui.fillTable(getTable());
			
			trafDest.isUsingAlternateRoute = true;

			trafDest.timeLastRedirect = System.currentTimeMillis();
			
  		return true;
		}
		else
		{
			trafDest.timeLastRedirect = System.currentTimeMillis();
			
			return false;
		}
  }
	
  /**
   * Reverts one bucket of traffic back to the primary path.
   * @param   trafDest  the traffic destination
   * @return  success of operation
   */
	private boolean revert(TrafficDestination trafDest)
	{
		int primaryRoute = trafDest.primaryRoute;
		int alternateRoute = (trafDest.primaryRoute + 1) % (2 * MAX_ROUTES);
		
		if (trafDest.currentSplit[0] <= 90)
		{
			String primaryKey = trafDest.destination.toString() + primaryRoute;
    	BestEffortTableEntry primaryEntry = (BestEffortTableEntry) get(primaryKey);
			primaryEntry.setSplit(primaryEntry.getSplit() + 10);
			trafDest.currentSplit[0] += 10;
			
			String alternateKey = trafDest.destination.toString() + alternateRoute;
			BestEffortTableEntry alternateEntry = (BestEffortTableEntry) get(alternateKey);
			alternateEntry.setSplit(alternateEntry.getSplit() - 10);
			trafDest.currentSplit[1] -= 10;
			
			gui.fillTable(getTable());
			
			if (trafDest.currentSplit[1] == 0)
			{
				trafDest.isUsingAlternateRoute = false;
			}

			trafDest.timeLastRevert = System.currentTimeMillis();
			
  		return true;
		}
		else
		{
			trafDest.timeLastRedirect = System.currentTimeMillis();
			
			return false;
		}
	}
  
  /**
   * Required method for MessageProcessors.
   * @return message types processed
   */
  public byte[] getMessageTypes()
  {
    return myMessages;
  }
  
  
  /**
   * Required method for SaamListeners.
   * @param   se  event received
   */
  public void receiveEvent(SaamEvent se){ }

  /**
   * If a BestEffortTableEntry has already been constructed,
   * this method allows it to be entered into the table.
   * @param entry The BestEffortTableEntry to be entered.
   */
  public synchronized void add (BestEffortTableEntry betentry)
  {
  	String key = betentry.getDestAddr().toString() + betentry.getSerialNo();  
  	put(key, betentry);
  	gui.fillTable(getTable());     
  }
  
  /**
   * Returns the contents of the best effort table
   * in the form of a String (useful for displaying the table).
   * @return A String representation of the contents of the
   *         entire table.
   */
  public String toString() 
  {
    String result = "Best Effort Table\n";
  
    Enumeration enum = elements();
    while (enum.hasMoreElements())
    {
      BestEffortTableEntry nextEntry = (BestEffortTableEntry) (enum.nextElement());
      result += nextEntry.toString() + "\n";
    }
    
    return result;
  }//toString()
  
}//end BestEffortTable