/**
 *  OAIPMHImporter
 *  Copyright 2009 by Michael Peter Christen
 *  First released 30.09.2009 at http://yacy.net
 *  
 *  This is a part of YaCy, a peer-to-peer based web search engine
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *  
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.document.importer;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.text.ParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.yacy.kelondro.data.meta.DigestURI;
import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.util.DateFormatter;
import net.yacy.repository.LoaderDispatcher;

import de.anomic.search.Switchboard;

// list records from oai-pmh like
// http://opus.bsz-bw.de/fhhv/oai2/oai2.php?verb=ListRecords&metadataPrefix=oai_dc

public class OAIPMHImporter extends Thread implements Importer, Comparable<OAIPMHImporter> {

    private static int importerCounter = Integer.MAX_VALUE;
    private static Object N = new Object();
    
    public static ConcurrentHashMap<OAIPMHImporter, Object> startedJobs = new ConcurrentHashMap<OAIPMHImporter, Object>();
    public static ConcurrentHashMap<OAIPMHImporter, Object> runningJobs = new ConcurrentHashMap<OAIPMHImporter, Object>();
    public static ConcurrentHashMap<OAIPMHImporter, Object> finishedJobs = new ConcurrentHashMap<OAIPMHImporter, Object>();
    
    private final LoaderDispatcher loader;
    private DigestURI source;
    private int recordsCount, chunkCount, completeListSize;
    private final long startTime;
    private long finishTime;
    private final ResumptionToken resumptionToken;
    private String message;
    private final int serialNumber;
    
    public OAIPMHImporter(LoaderDispatcher loader, DigestURI source) {
        this.serialNumber = importerCounter--;
        this.loader = loader;
        this.recordsCount = 0;
        this.chunkCount = 0;
        this.completeListSize = 0;
        this.startTime = System.currentTimeMillis();
        this.finishTime = 0;
        this.resumptionToken = null;
        this.message = "import initialized";
        // fix start url
        String url = ResumptionToken.truncatedURL(source);
        if (!url.endsWith("?")) url = url + "?";
        try {
            this.source = new DigestURI(url + "verb=ListRecords&metadataPrefix=oai_dc");
        } catch (MalformedURLException e) {
            // this should never happen
            Log.logException(e);
        }
        startedJobs.put(this, N);
    }

    public int count() {
        return this.recordsCount;
    }
    
    public int chunkCount() {
        return this.chunkCount;
    }
    
    public String status() {
        return this.message;
    }
    
    public ResumptionToken getResumptionToken() {
        return this.resumptionToken;
    }

    public int getCompleteListSize() {
        return this.completeListSize;
    }
    
    public long remainingTime() {
        return (this.isAlive()) ? Long.MAX_VALUE : 0; // we don't know
    }

    public long runningTime() {
        return (this.isAlive()) ? System.currentTimeMillis() - this.startTime : this.finishTime - this.startTime;
    }

    public String source() {
        return source.toNormalform(true, false);
    }

    public int speed() {
        return (int) (1000L * ((long) count()) / runningTime());
    }
    
    public void run() {
        while (runningJobs.size() > 50) {
            try {Thread.sleep(10000 + 3000 * (System.currentTimeMillis() % 6));} catch (InterruptedException e) {}
        }
        startedJobs.remove(this);
        runningJobs.put(this, N);
        this.message = "loading first part of records";
        while (true) {
            try {
                OAIPMHLoader loader = new OAIPMHLoader(this.loader, this.source, Switchboard.getSwitchboard().surrogatesInPath, filenamePrefix);
                this.completeListSize = Math.max(this.completeListSize, loader.getResumptionToken().getCompleteListSize());
                this.chunkCount++;
                this.recordsCount += loader.getResumptionToken().getRecordCounter();
                this.source = loader.getResumptionToken().resumptionURL();
                if (this.source == null) {
                    this.message = "import terminated with source = null";
                    break;
                }
                this.message = "loading next resumption fragment, cursor = " + loader.getResumptionToken().getCursor();
            } catch (IOException e) {
                this.message = e.getMessage();
                break;
            }
        }
        this.finishTime = System.currentTimeMillis();
        runningJobs.remove(this);
        finishedJobs.put(this, N);
    }
    
    
    // methods that are needed to put the object into a Hashtable or a Map:
    
    public int hashCode() {
        return this.serialNumber;
    }
    
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (!(obj instanceof OAIPMHImporter)) return false;
        OAIPMHImporter other = (OAIPMHImporter) obj;
        return this.compareTo(other) == 0;
    }

    // methods that are needed to put the object into a Tree:
    public int compareTo(OAIPMHImporter o) {
        if (this.serialNumber > o.serialNumber) return 1;
        if (this.serialNumber < o.serialNumber) return -1;
        return 0;
    }
    
    public static Set<String> getUnloadedOAIServer(
            LoaderDispatcher loader,
            File surrogatesIn,
            File surrogatesOut,
            long staleLimit) {
        Set<String> plainList = OAIListFriendsLoader.getListFriends(loader).keySet();
        Map<String, Date> loaded = getLoadedOAIServer(surrogatesIn, surrogatesOut);
        long limit = System.currentTimeMillis() - staleLimit;
        for (Map.Entry<String, Date> a: loaded.entrySet()) {
            if (a.getValue().getTime() > limit) plainList.remove(a.getKey());
        }
        return plainList;
    }

    /**
     * get a map for already loaded oai-pmh servers and their latest access date
     * @param surrogatesIn
     * @param surrogatesOut
     * @return a map where the key is the hostID of the servers and the value is the last access date
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Date> getLoadedOAIServer(File surrogatesIn, File surrogatesOut) {
        Map<String, Date> map = getLoadedOAIServer(surrogatesOut);
        map.putAll((Map<? extends String, ? extends Date>) getLoadedOAIServer(surrogatesIn).entrySet());
        return map;
    }
    
    private static Map<String, Date> getLoadedOAIServer(File surrogates) {
        HashMap<String, Date> map = new HashMap<String, Date>();
        //oaipmh_opus.bsz-bw.de_20091102113118728.xml
        for (String s: surrogates.list()) {
            if (s.startsWith(filenamePrefix) && s.endsWith(".xml") && s.charAt(s.length() - 22) == filenameSeparationChar) {
                try {
                    Date fd = DateFormatter.parseShortMilliSecond(s.substring(s.length() - 21, s.length() - 4));
                    String hostID = s.substring(7, s.length() - 22);
                    Date md = map.get(hostID);
                    if (md == null || fd.after(md)) map.put(hostID, fd);
                } catch (ParseException e) {
                    Log.logException(e);
                }
            }
        }
        return map;
    }

    public static final char hostReplacementChar = '_';
    public static final char filenameSeparationChar = '.';
    public static final String filenamePrefix = "oaipmh";

    /**
     * compute a host id that is also used in the getLoadedOAIServer method for the map key
     * @param source
     * @return a string that is a key for the given host
     */
    public static final String hostID(DigestURI source) {
        String s = ResumptionToken.truncatedURL(source);
        if (s.endsWith("?")) s = s.substring(0, s.length() - 1);
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.startsWith("https://")) s = s.substring(8);
        if (s.startsWith("http://")) s = s.substring(7);
        return s.replace('.', hostReplacementChar).replace('/', hostReplacementChar).replace(':', hostReplacementChar);
    }
    
    /**
     * get a file name for a source. the file name contains a prefix that is used to identify
     * that source as part of the OAI-PMH import process and a host key to identify the source.
     * also included is a date stamp within the file name
     * @param source
     * @return a file name for the given source. It will be different for each call for same hosts because it contains a date stamp
     */
    public static final String filename4Source(DigestURI source) {
        return filenamePrefix + OAIPMHImporter.filenameSeparationChar +
               OAIPMHImporter.hostID(source) + OAIPMHImporter.filenameSeparationChar +
               DateFormatter.formatShortMilliSecond(new Date()) + ".xml";
    }
}