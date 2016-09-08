//25Feb02[Wofford] - Sudafed renamed Congestion Advisory.
//31Jan02[Wu] - repackaged
//29Jan02[Wofford] - Rewrote to conform to standard format (like DCM)
//07Dec01[Wofford] - Created.

package org.saamnet.saam.message;

import java.net.UnknownHostException;
import java.util.*;
import org.saamnet.saam.net.*;
import org.saamnet.saam.util.*;

/**
 * CongestionAdvisory is how a server tells a router whether or not it is
 * experiencing congestion of its best effort traffic.  It is
 * also how it tells the router that congestion is relieved. 
 */
public class CongestionAdvisory extends Message{

  public static final byte GREEN  = 0;
  public static final byte YELLOW = 1;
  public static final byte RED    = 2;
  
  //total length (in bytes) of fields below
  private final static short CADV_LENGTH = (short) (4 + 1);
  
  int pathID;
  byte pathCondition;

  public CongestionAdvisory(int pathID, byte pathCondition)
  {
    super(Message.CONGESTION_ADVISORY);
    this.pathID = pathID;
    this.pathCondition = pathCondition;
		
    bytes = Array.concat(type, PrimitiveConversions.getBytes(CADV_LENGTH));
		bytes = Array.concat(bytes, PrimitiveConversions.getBytes(pathID));
		bytes = Array.concat(bytes, pathCondition);
  }

  public CongestionAdvisory (byte[] bytes)
  {
    super(Message.CONGESTION_ADVISORY);
    this.bytes = bytes;

		int index = 3;//skip type and length fields
		  
    pathID = PrimitiveConversions.getInt(Array.getSubArray(bytes, index, index +4));
    index += 4;
		
    pathCondition = bytes[index];

  }//end byte array based Constructor

  public int getPathID()
  {
    return pathID;
  }
  
  public byte pathCondition()
  {
  	return pathCondition;
  }

  public String toString()
  {                       
    String advisory = "Congestion Advisory Message:" + 
                     "\n  Path ID = " + pathID +
                     "; Traffic condition = ";
		switch (pathCondition)
		{
		case GREEN:
			advisory += "GREEN";
			break;
		case YELLOW:
			advisory += "YELLOW";
			break;
		case RED:
			advisory += "RED";
			break;
		default:
			break;
		}
					
    return advisory;
  }
   
}