package com.netflix.edda.mongo

import com.netflix.edda.Elector
import com.netflix.edda.ConfigContext

import com.weiglewilczek.slf4s.Logger

import org.joda.time.DateTime
    
import com.mongodb.DBCollection

class MongoElector(ctx: ConfigContext) extends Elector(ctx)  {
    private[this] val logger = Logger(getClass)

    val instance = Option(
        System.getenv( ctx.config.getProperty("edda.mongo.elector.uniqueEnvName", "EC2_INSTANCE_ID") )
    ).getOrElse("dev")
    val name = ctx.config.getProperty("edda.mongo.elector.collectionName", "sys.monitor")
    val mongo: DBCollection = try {
        MongoDatastore.mongoCollection(name, ctx)
    } catch {
        case e => {
            logger.error("exception", e)
            null
        }
    }
    val leaderTimeout = ctx.config.getProperty("edda.mongo.elector.leaderTimeout", "5000").toInt

    override
    def init() = {
        super.init
    }

    protected override
    def runElection(): Boolean = {
        val now = DateTime.now
        var leader = instance

        var isLeader = false

        val rec = mongo.findOne("leader")
        if( rec == null ) {
            // nobody is leader so try to become leader
            val wr = mongo.insert(
                MongoDatastore.mapToMongo(
                    Map(
                        "_id" -> "leader",
                        "id" -> "leader",
                        "ctime" -> now,
                        "mtime" -> now,
                        "stime" -> now,
                        "ltime" -> null,
                        "data"  -> Map("instance" -> instance, "id" -> "leader", "type" -> "leader")
                    )
                )
            )
            // if we got an error then uniqueness failed (someone else beat us to it)
	        isLeader = if( wr.getError == null ) true else false
        } else {
            val r = MongoDatastore.mongoToRecord(rec);
            leader = r.data.asInstanceOf[Map[String,Any]]("instance").asInstanceOf[String];
            val mtime  = r.mtime;
            if( leader == instance ) {
                // update mtime
                val result = mongo.findAndModify(
                    MongoDatastore.mapToMongo(Map(
                        "_id" -> "leader",
                        "data.instance" -> instance
                    )),   // query
                    null, // sort
                    MongoDatastore.mapToMongo(Map("$set" -> Map("mtime" -> now))) // update
                )
                // maybe we were too slow and someone took leader from us
                isLeader = if( result == null ) false else true
            } else {
                val timeout = DateTime.now().plusMillis( -1 * (pollCycle + leaderTimeout))
                if( mtime.isBefore(timeout) ) {
                    // assumer leader is dead, so try to become leader
                    val result = mongo.findAndModify(
                        MongoDatastore.mapToMongo(Map( // query
                            "_id" -> "leader",
                            "data.instance" -> leader,
                            "mtime" -> mtime
                        )),
                        null,           // sort
                        MongoDatastore.recordToMongo(  // update
                            r.copy(
                                mtime = now,
                                stime = now,
                                ltime = null,
                                data  = Map("instance" -> instance, "id" -> "leader", "type" -> "leader")
                            ),
                            Some("leader")
                        )
                    )
                    // if we got the update then we are leader and attempt to 
                    // archive the old leader record
                    if( result == null ) {
                        isLeader = false
                    } else {
                        isLeader = true
                        mongo.insert(
                            MongoDatastore.recordToMongo(r.copy(ltime = now),Some("leader|" + r.stime.getMillis))
                        )
                    }
                } else isLeader = false
            }
        }

        logger.info("Leader [" + instance + "]: " + isLeader + " [" + leader + "]");
        isLeader
    }

    override
    def toString = "[Elector mongo]"
}
