// PeerSelection.java 
// -------------------------------------
// (C) by Michael Peter Christen; mc@yacy.net
// first published 05.11.2008 on http://yacy.net
// Frankfurt, Germany, 2008
//
// $LastChangedDate: 2008-09-03 02:30:21 +0200 (Mi, 03 Sep 2008) $
// $LastChangedRevision: 5102 $
// $LastChangedBy: orbiter $
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package de.anomic.yacy.dht;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.yacy.cora.storage.DynamicScore;
import net.yacy.cora.storage.ScoreCluster;
import net.yacy.kelondro.data.word.Word;
import net.yacy.kelondro.index.HandleSet;
import net.yacy.kelondro.index.RowSpaceExceededException;
import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.order.Base64Order;
import net.yacy.kelondro.order.Digest;
import net.yacy.kelondro.util.DateFormatter;
import net.yacy.kelondro.util.kelondroException;

import de.anomic.yacy.yacyCore;
import de.anomic.yacy.yacySeed;
import de.anomic.yacy.yacySeedDB;
import de.anomic.yacy.yacyVersion;


/*
 * this package is a collection of peer selection iterations that had been
 * part of yacyPeerActions, yacyDHTActions and yacySeedDB
 */

public class PeerSelection {
    
    public static void selectDHTPositions(
            final yacySeedDB seedDB, 
            byte[] wordhash,
            int redundancy, 
            Map<String, yacySeed> regularSeeds,
            DynamicScore<String> ranking) {
        // this method is called from the search target computation
        final long[] dhtVerticalTargets = seedDB.scheme.dhtPositions(wordhash);
        yacySeed seed;
        for (long  dhtVerticalTarget : dhtVerticalTargets) {
            wordhash = FlatWordPartitionScheme.positionToHash(dhtVerticalTarget);
            Iterator<yacySeed> dhtEnum = getAcceptRemoteIndexSeeds(seedDB, wordhash, redundancy, false);
            int c = Math.min(seedDB.sizeConnected(), redundancy);
            int cc = 3; // select a maximum of 3, this is enough redundancy
            while (dhtEnum.hasNext() && c > 0 && cc-- > 0) {
                seed = dhtEnum.next();
                if (seed == null || seed.hash == null) continue;
                if (!seed.getFlagAcceptRemoteIndex()) continue; // probably a robinson peer
                if (Log.isFine("PLASMA")) Log.logFine("PLASMA", "selectPeers/DHTorder: " + seed.hash + ":" + seed.getName() + "/ score " + c);
                ranking.inc(seed.hash, 2 * c);
                regularSeeds.put(seed.hash, seed);
                c--;
            }
        }
    }
    
    private static int guessedOwn = 0;
    
    public static boolean shallBeOwnWord(final yacySeedDB seedDB, final byte[] wordhash, final String urlhash, final int redundancy) {
        // the guessIfOwnWord is a fast method that should only fail in case that a 'true' may be incorrect, but a 'false' shall always be correct
        if (guessIfOwnWord(seedDB, wordhash, urlhash)) {
            // this case must be verified, because it can be wrong.
            guessedOwn++;
            return verifyIfOwnWord(seedDB, wordhash, urlhash, redundancy);
        } else {
            return false;
        }
        
    }
    
    private static boolean guessIfOwnWord(final yacySeedDB seedDB, final byte[] wordhash, final String urlhash) {
        if (seedDB == null) return false;
        int connected = seedDB.sizeConnected();
        if (connected == 0) return true;
        final long target = seedDB.scheme.dhtPosition(wordhash, urlhash);
        final long mypos = seedDB.scheme.dhtPosition(seedDB.mySeed().hash.getBytes(), urlhash);
        long distance = FlatWordPartitionScheme.dhtDistance(target, mypos);
        if (distance <= 0) return false;
        if (distance <= Long.MAX_VALUE / connected * 2) return true;
        return false;
    }
    
    private static boolean verifyIfOwnWord(final yacySeedDB seedDB, byte[] wordhash, String urlhash, int redundancy) {
        String myHash = seedDB.mySeed().hash;
        wordhash = FlatWordPartitionScheme.positionToHash(seedDB.scheme.dhtPosition(wordhash, urlhash));
        final Iterator<yacySeed> dhtEnum = getAcceptRemoteIndexSeeds(seedDB, wordhash, redundancy, true);
        while (dhtEnum.hasNext()) {
            if (dhtEnum.next().hash.equals(myHash)) return true;
        }
        return false;
    }
    
    public static byte[] selectTransferStart() {
        return Base64Order.enhancedCoder.encode(Digest.encodeMD5Raw(Long.toString(System.currentTimeMillis()))).substring(2, 2 + Word.commonHashLength).getBytes();
    }
    
    public static byte[] limitOver(final yacySeedDB seedDB, final byte[] startHash) {
        final Iterator<yacySeed> seeds = getAcceptRemoteIndexSeeds(seedDB, startHash, 1, false);
        if (seeds.hasNext()) return seeds.next().hash.getBytes();
        return null;
    }

    protected static List<yacySeed> getAcceptRemoteIndexSeedsList(
            yacySeedDB seedDB,
            final byte[] starthash,
            int max,
            boolean alsoMyOwn) {
        final Iterator<yacySeed> seedIter = PeerSelection.getAcceptRemoteIndexSeeds(seedDB, starthash, max, alsoMyOwn);
        final ArrayList<yacySeed> targets = new ArrayList<yacySeed>();
        while (seedIter.hasNext() && max-- > 0) targets.add(seedIter.next());
        return targets;
    }
    
    /**
     * returns an enumeration of yacySeed-Objects that have the AcceptRemoteIndex-Flag set
     * the seeds are enumerated in the right order according to the DHT
     * @param seedDB
     * @param starthash
     * @param max
     * @param alsoMyOwn
     * @return
     */
    public static Iterator<yacySeed> getAcceptRemoteIndexSeeds(final yacySeedDB seedDB, final byte[] starthash, final int max, final boolean alsoMyOwn) {
        return new acceptRemoteIndexSeedEnum(seedDB, starthash, Math.min(max, seedDB.sizeConnected()), alsoMyOwn);
    }
    
    private static class acceptRemoteIndexSeedEnum implements Iterator<yacySeed> {

        private final Iterator<yacySeed> se;
        private yacySeed nextSeed;
        private final yacySeedDB seedDB;
        private final HandleSet doublecheck;
        private int remaining;
        private boolean alsoMyOwn;
        
        public acceptRemoteIndexSeedEnum(yacySeedDB seedDB, final byte[] starthash, int max, boolean alsoMyOwn) {
            this.seedDB = seedDB;
            this.se = getDHTSeeds(seedDB, starthash, yacyVersion.YACY_HANDLES_COLLECTION_INDEX);
            this.remaining = max;
            this.doublecheck = new HandleSet(12, Base64Order.enhancedCoder, 0);
            this.nextSeed = nextInternal();
            this.alsoMyOwn = alsoMyOwn && nextSeed != null && (Base64Order.enhancedCoder.compare(seedDB.mySeed().hash.getBytes(), nextSeed.hash.getBytes()) > 0);
        }
        
        public boolean hasNext() {
            return nextSeed != null;
        }

        private yacySeed nextInternal() {
            if (this.remaining <= 0) return null;
            yacySeed s;
            try {
                while (se.hasNext()) {
                    s = se.next();
                    if (s == null) return null;
                    byte[] hashb = s.hash.getBytes();
                    if (doublecheck.has(hashb)) return null;
                    try {
                        this.doublecheck.put(hashb);
                    } catch (RowSpaceExceededException e) {
                        Log.logException(e);
                        break;
                    }
                    if (s.getFlagAcceptRemoteIndex()) {
                        this.remaining--;
                        return s;
                    }
                }
            } catch (final kelondroException e) {
                System.out.println("DEBUG acceptRemoteIndexSeedEnum:" + e.getMessage());
                yacyCore.log.logSevere("database inconsistency (" + e.getMessage() + "), re-set of db.");
                seedDB.resetActiveTable();
                return null;
            }
            return null;
        }
        
        public yacySeed next() {
            if (alsoMyOwn && Base64Order.enhancedCoder.compare(seedDB.mySeed().hash.getBytes(), nextSeed.hash.getBytes()) < 0) {
                // take my own seed hash instead the enumeration result
                alsoMyOwn = false;
                return seedDB.mySeed();
            } else {
                final yacySeed next = nextSeed;
                nextSeed = nextInternal();
                return next;
            }
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }

    }
    
    /**
     * enumerate seeds for DHT target positions
     * @param seedDB
     * @param firstHash
     * @param minVersion
     * @return
     */
    protected static Iterator<yacySeed> getDHTSeeds(final yacySeedDB seedDB, final byte[] firstHash, final float minVersion) {
        // enumerates seed-type objects: all seeds with starting point in the middle, rotating at the end/beginning
        return new seedDHTEnum(seedDB, firstHash, minVersion);
    }

    private static class seedDHTEnum implements Iterator<yacySeed> {

        private Iterator<yacySeed> e1, e2;
        private int steps;
        private float minVersion;
        private yacySeedDB seedDB;
        
        public seedDHTEnum(final yacySeedDB seedDB, final byte[] firstHash, final float minVersion) {
            this.seedDB = seedDB;
            this.steps = seedDB.sizeConnected();
            this.minVersion = minVersion;
            this.e1 = seedDB.seedsConnected(true, false, firstHash, minVersion);
            this.e2 = null;
        }
        
        public boolean hasNext() {
            return (steps > 0) && ((e2 == null) || (e2.hasNext()));
        }

        public yacySeed next() {
            if (steps == 0) return null;
            steps--;
            
            if (e1 == null || !e1.hasNext()) {
                if (e2 == null) {
                    e1 = null;
                    e2 = seedDB.seedsConnected(true, false, null, minVersion);
                }
                return e2.next();
            }
            
            final yacySeed n = e1.next();
            if (!(e1.hasNext())) {
                e1 = null;
                e2 = seedDB.seedsConnected(true, false, null, minVersion);
            }
            return n;
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
    
    /**
     * enumerate peers that provide remote crawl urls
     * @param seedDB
     * @return an iterator of seed objects
     */
    public static Iterator<yacySeed> getProvidesRemoteCrawlURLs(final yacySeedDB seedDB) {
        return new providesRemoteCrawlURLsEnum(seedDB);
    }
    
    private static class providesRemoteCrawlURLsEnum implements Iterator<yacySeed> {

        private Iterator<yacySeed> se;
        private yacySeed nextSeed;
        private yacySeedDB seedDB;
        
        public providesRemoteCrawlURLsEnum(final yacySeedDB seedDB) {
            this.seedDB = seedDB;
            se = getDHTSeeds(seedDB, null, yacyVersion.YACY_POVIDES_REMOTECRAWL_LISTS);
            nextSeed = nextInternal();
        }
        
        public boolean hasNext() {
            return nextSeed != null;
        }

        private yacySeed nextInternal() {
            yacySeed s;
            try {
                while (se.hasNext()) {
                    s = se.next();
                    if (s == null) return null;
                    if (s.getLong(yacySeed.RCOUNT, 0) > 0) return s;
                }
            } catch (final kelondroException e) {
                System.out.println("DEBUG providesRemoteCrawlURLsEnum:" + e.getMessage());
                yacyCore.log.logSevere("database inconsistency (" + e.getMessage() + "), re-set of db.");
                seedDB.resetActiveTable();
                return null;
            }
            return null;
        }
        
        public yacySeed next() {
            final yacySeed next = nextSeed;
            nextSeed = nextInternal();
            return next;
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }

    }

    /**
     * get either the youngest or oldest peers from the seed db. Count as many as requested
     * @param seedDB
     * @param up if up = true then get the most recent peers, if up = false then get oldest
     * @param count number of wanted peers
     * @return a hash map of peer hashes to seed object
     */
    public static Map<String, yacySeed> seedsByAge(final yacySeedDB seedDB, final boolean up, int count) {
        
        if (count > seedDB.sizeConnected()) count = seedDB.sizeConnected();

        // fill a score object
        final DynamicScore<String> seedScore = new ScoreCluster<String>();
        yacySeed ys;
        long absage;
        final Iterator<yacySeed> s = seedDB.seedsConnected(true, false, null, (float) 0.0);
        int searchcount = 1000;
        if (searchcount > seedDB.sizeConnected()) searchcount = seedDB.sizeConnected();
        try {
            while ((s.hasNext()) && (searchcount-- > 0)) {
                ys = s.next();
                if ((ys != null) && (ys.get(yacySeed.LASTSEEN, "").length() > 10)) try {
                    absage = Math.abs(System.currentTimeMillis() + DateFormatter.dayMillis - ys.getLastSeenUTC()) / 1000 / 60;
                    if (absage > Integer.MAX_VALUE) absage = Integer.MAX_VALUE;
                    seedScore.inc(ys.hash, (int) absage); // the higher absage, the older is the peer
                } catch (final Exception e) {}
            }
            
            // result is now in the score object; create a result vector
            final Map<String, yacySeed> result = new HashMap<String, yacySeed>();
            final Iterator<String> it = seedScore.keys(up);
            int c = 0;
            while ((c < count) && (it.hasNext())) {
                c++;
                ys = seedDB.getConnected(it.next());
                if ((ys != null) && (ys.hash != null)) result.put(ys.hash, ys);
            }
            return result;
        } catch (final kelondroException e) {
            yacyCore.log.logSevere("Internal Error at yacySeedDB.seedsByAge: " + e.getMessage(), e);
            return null;
        }
    }
}
