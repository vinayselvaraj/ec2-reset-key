
import com.amazonaws.services.ec2.*
import com.amazonaws.services.ec2.model.*
import org.apache.log4j.*
import groovy.util.logging.*

LOG = Logger.getInstance(getClass())

def cli = new CliBuilder(usage:'ec2-reset-key -i <instance id> -k <keypair name>')

cli.i(longOpt: 'instance-id', required: true, args: 1, 'Instance ID of the EC2 instance (e.g. i-XXXXXX)')
cli.r(longOpt: 'region',      required: true, args: 1, 'region (us-east-1, us-west-1, etc...)')
cli.t(longOpt: 'type',        required: true, args: 1, 'Type of instance (i.e. linux, windows)')
cli.f(longOpt: 'publicKey',   required: false, args: 1, 'Linux Only: Path to public key file (i.e. ~/mypublic_key.pub)')
cli.h(longOpt: 'help', 'help')

def opts = cli.parse(args)

if(!opts) {
  return
}

if(opts.h) {
  cli.usage()
  return
}

if(opts.t == "linux" && !opts.f) {
  System.err.println("ERROR: Public key file is required for linux instances")
  return
}

if(opts.t != "linux" && opts.t != "windows") {
  System.err.println("ERROR: Type must be 'linux' or 'windows'")
  return
}

// Get option values
instanceId  = opts.i
region      = opts.r

// Default Configs
InstanceType recoveryInstanceType    = InstanceType.T1Micro
String       recoveryBlockDeviceName = "/dev/sdh"

// Setup AMI region map
def amiRegionMap = new HashMap()
amiRegionMap.put("us-east-1"     , "ami-98aa1cf0")
amiRegionMap.put("us-west-1"     , "ami-1b3b462b")
amiRegionMap.put("us-west-2"     , "ami-a8d3d4ed")
amiRegionMap.put("eu-west-1"     , "ami-f6b11181")
amiRegionMap.put("ap-southeast-1", "ami-2ce7c07e")
amiRegionMap.put("ap-southeast-2", "ami-1f117325")
amiRegionMap.put("ap-northeast-1", "ami-df4b60de")
amiRegionMap.put("sa-east-1"     , "ami-71d2676c")

// Setup region endpoints
def regionEndpoints = new HashMap()
regionEndpoints.put("us-east-1",      "ec2.us-east-1.amazonaws.com")
regionEndpoints.put("us-west-1",      "ec2.us-west-1.amazonaws.com")
regionEndpoints.put("us-west-2",      "ec2.us-west-2.amazonaws.com")
regionEndpoints.put("eu-west-1",      "ec2.eu-west-1.amazonaws.com")
regionEndpoints.put("ap-southeast-1", "ec2.ap-southeast-1.amazonaws.com")
regionEndpoints.put("ap-southeast-2", "ec2.ap-southeast-2.amazonaws.com")
regionEndpoints.put("ap-northeast-1", "ec2.ap-northeast-1.amazonaws.com")
regionEndpoints.put("sa-east-1",      "ec2.sa-east-1.amazonaws.com")

// Setup userdata
String userDataStr = this.getClass().getResource('userdata.sh').text
userDataStr = userDataStr.replaceAll("__TYPE__", opts.t)
if(opts.f) {
  sshKey = new File(opts.f).text
  userDataStr = userDataStr.replaceAll("__SSH_KEY__", sshKey)
}
String userDataBase64 = userDataStr.bytes.encodeBase64().toString()

String ec2Endpoint = regionEndpoints.get(region)
if(ec2Endpoint == null) {
  LOG.error("Unable to find EC2 endpoint for region '${region}'")
  System.exit(1)
}

String amiId = amiRegionMap.get(region)
if(amiId == null) {
  LOG.error("Unable to find AMI for region '${region}'")
  System.exit(1)
}

// Setup EC2 Client
AmazonEC2 ec2Client = new AmazonEC2Client()
ec2Client.setEndpoint(ec2Endpoint)

// Get all EC2 instances in the selected region
List<Reservation> reservations = ec2Client.describeInstances().getReservations()
List<Instance> instances = new ArrayList()
for(Reservation reservation : reservations) {
  instances.addAll(reservation.getInstances())
}

// Find the selected instance & AZ
Instance targetInstance = null
String availabilityZone = null
for(Instance instance : instances) {
  if(instance.getInstanceId().equals(instanceId)) {
    targetInstance = instance
    availabilityZone = targetInstance.placement.availabilityZone
    break
  }
}

// Check to see if instance was found
if(targetInstance == null) {
  LOG.error("Unable to find instance in ${region} with ID ${instanceId}")
  System.exit(1)
}

// Break out if root device type is not EBS
rootDeviceType = targetInstance.getRootDeviceType()
if(rootDeviceType != "ebs") {
  LOG.error("Root device type must be EBS")
  System.exit(1)
}

// Get the instance state and break out if instance is not running
state = targetInstance.getState().getName()
if(state != "running") {
  LOG.error("Instance is not running.  Instance must be started manually before this tool can be used.")
  System.exit(1)
}

LOG.info("Started key/password reset on ${instanceId}")

// Get the block device mapping & root device
blockDeviceMappings      = targetInstance.getBlockDeviceMappings()
targetRootDeviceName     = targetInstance.getRootDeviceName()
targetEbsVolumeId        = null
targetBlockDeviceMapping = null

// Loop through mappings and get the root device mapping
for(InstanceBlockDeviceMapping mapping : blockDeviceMappings) {
  if(mapping.getDeviceName().equals(targetRootDeviceName)) {
    targetBlockDeviceMapping = mapping
    break
  }
}
targetEbsVolumeId = targetBlockDeviceMapping.getEbs().getVolumeId()

// Sanity check
if(targetBlockDeviceMapping == null) {
  Log.error("Unable to get the block device mapping for the root EBS volume")
  System.exit(1)
}

// Stop the instance
stopInstance(ec2Client, instanceId)

// Detach target EBS volume
detachEbsVolume(ec2Client, instanceId, targetEbsVolumeId)

// Launch recovery instance with target EBS volume
RunInstancesRequest runInstancesRequest = new RunInstancesRequest()
runInstancesRequest.setImageId(amiId)
runInstancesRequest.setInstanceType(recoveryInstanceType)
runInstancesRequest.setMinCount(1)
runInstancesRequest.setMaxCount(1)
runInstancesRequest.setUserData(userDataBase64)
runInstancesRequest.setPlacement(new Placement().withAvailabilityZone(availabilityZone))
LOG.info("About to launch recovery instance ...")
runInstancesResult = ec2Client.runInstances(runInstancesRequest)
recoveryInstanceId = runInstancesResult.reservation.instances.get(0).instanceId
LOG.info("Launched recovery instance ${recoveryInstanceId}")

// Wait for receovery instance to start
LOG.info("Waiting for recovery instance ${recoveryInstanceId} to start")
if(!waitForInstanceToChangeState(ec2Client, recoveryInstanceId, "running", 10)) {
  LOG.error("Timed out while waiting for recovery instance to start ${recoveryInstanceId}")
  System.exit(1)
}
LOG.info("Recovery instance ${recoveryInstanceId} has started")


// Attach the EBS volume to the recovery instance
attachEbsVolume(ec2Client, recoveryInstanceId, targetEbsVolumeId, recoveryBlockDeviceName)


// Wait for recovery instance to complete actions then stop on its own
LOG.info("Waiting for recovery instance ${recoveryInstanceId} to complete password/key reset and stop")
if(!waitForInstanceToChangeState(ec2Client, recoveryInstanceId, "stopped", 10)) {
  LOG.error("Timed out while waiting for recovery instance to stop")
  System.exit(1)
}

// Detach target EBS volume from recovery instance
detachEbsVolume(ec2Client, recoveryInstanceId, targetEbsVolumeId)
LOG.info("Terminated recovery instance ${recoveryInstanceId}")

// Terminate recovery instance
ec2Client.terminateInstances(new TerminateInstancesRequest().withInstanceIds(recoveryInstanceId))

// Attach target EBS volume back to target instance
attachEbsVolume(ec2Client, instanceId, targetEbsVolumeId, targetRootDeviceName)

// Start target instance
startInstance(ec2Client, instanceId)

// Complete
LOG.info("-----   COMPLETED.  PLEASE WAIT FOR INSTANCE TO START UP -----")


void startInstance(ec2Client, instanceId) {
  LOG.info("Starting instance ${instanceId}")
  ec2Client.startInstances(new StartInstancesRequest().withInstanceIds(instanceId))
  
  // Wait for instance to start
  LOG.info("Waiting for instance ${instanceId} to start...")
  if(!waitForInstanceToChangeState(ec2Client, instanceId, "running", 10)) {
    LOG.error("Timed out while starting instance ${instanceId}")
    System.exit(1)
  }
}

void stopInstance(ec2Client, instanceId) {
  LOG.info("Stopping instance ${instanceId}")
  ec2Client.stopInstances(new StopInstancesRequest().withInstanceIds(instanceId))

  // Wait for instance to stop
  LOG.info("Waiting for instance ${instanceId} to stop...")
  if(!waitForInstanceToChangeState(ec2Client, instanceId, "stopped", 10)) {
    LOG.error("Timed out while stopping instance ${instanceId}")
    System.exit(1)
  }
}

void attachEbsVolume(ec2Client, instanceId, volumeId, blockDeviceName) {
  attachVolumeResult = ec2Client.attachVolume(new AttachVolumeRequest(volumeId, instanceId, blockDeviceName))
  LOG.info("Attached ${volumeId} to ${instanceId}")

  // Wait for target EBS volume to complete attaching
  LOG.info("Waiting for ${volumeId} to attach to ${instanceId}")
  if(!waitForEbsAttachmentToChangeState(ec2Client, volumeId, "attached", 10)) {
    LOG.error("Timed out while waiting for ${volumeId} to attach to ${instanceId}")
    System.exit(1)
  }
  LOG.info("Successfully attached ${volumeId} to ${instanceId}")
}

void detachEbsVolume(ec2Client, instanceId, volumeId) {
  
  // Detach target EBS volume
  LOG.info("About to detach EBS volume ${volumeId} from instance ${instanceId}...")
  detachVolumeResult = ec2Client.detachVolume(new DetachVolumeRequest(volumeId).withInstanceId(instanceId))

  // Wait for target EBS volume to complete detaching
  LOG.info("Waiting for ${volumeId} to detach from ${instanceId}")
  if(!waitForEbsAttachmentToChangeState(ec2Client, volumeId, "detached", 10)) {
    LOG.error("Timed out while waiting for ${instanceId} to detach from ${instanceId}")
    System.exit(1)
  }
  LOG.info("Successfully detached ${instanceId} from ${instanceId}")
  
}

boolean waitForEbsAttachmentToChangeState(ec2Client, volumeId, expectedState, timeoutInMinutes) {
  success = false
  done    = false
  
  timeout = System.currentTimeMillis() + (timeoutInMinutes * 60 * 1000)
  
  while(!done) {
    
    result = ec2Client.describeVolumes(new DescribeVolumesRequest().withVolumeIds(volumeId))
    volumes = result.getVolumes()
    
    if(volumes != null && volumes.size() == 1) {
      Volume volume = volumes.get(0)
      volumeAttachments = volume.getAttachments()
      if(expectedState.equals("detached") && (volumeAttachments == null || volumeAttachments.size() == 0) ) {
        success = true
        done    = true
      } else {
        if(volumeAttachments != null && volumeAttachments.size() >= 0) {
          volumeAttachment = volumeAttachments.get(0)
          if(volumeAttachment.getState().equals(expectedState)) {
            success = true
            done    = true
          }
        }
      }
    }
    
    if(done || System.currentTimeMillis() > timeout) {
      done = true
    } else {
      Thread.sleep(15 * 1000) // Sleep 15sec
    }
  }
  
  return success
}

boolean waitForInstanceToChangeState(ec2Client, instanceId, expectedState, timeoutInMinutes) {
  success = false
  done    = false
  
  timeout = System.currentTimeMillis() + (timeoutInMinutes * 60 * 1000)
  
  while (!done) {
    result = ec2Client.describeInstances(new DescribeInstancesRequest().withInstanceIds(instanceId))
    reservations = result.getReservations()
  
    Instance instance = null
    if(reservations != null && reservations.size() == 1) {
      instances = reservations.get(0).getInstances()
      if(instances != null && instances.size() == 1) {
        instance = instances.get(0)
      }
    }
  
    if(instance != null) {
      state = instance.getState().getName()
      if(state.equals(expectedState)) {
        done = true
        success = true
      }
    }
    
    if(done || System.currentTimeMillis() > timeout) {
      done = true
    } else {
      Thread.sleep(30 * 1000) // Sleep 30sec
    }
  }
  
  return success
}
