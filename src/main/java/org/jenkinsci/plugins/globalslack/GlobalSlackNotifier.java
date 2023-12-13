
package org.jenkinsci.plugins.globalslack;

import hudson.model.listeners.RunListener;
import hudson.model.*;
import hudson.EnvVars;
import hudson.Extension;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.AffectedFile;
import hudson.scm.ChangeLogSet.Entry;

import java.util.*;
import java.util.logging.Logger;

import jenkins.plugins.slack.*;



@Extension
public class GlobalSlackNotifier extends RunListener<Run<?, ?>> implements Describable<GlobalSlackNotifier> {

    private static final Logger logger = Logger.getLogger(GlobalSlackNotifier.class.getName());


    @Override
    public void onCompleted(Run<?, ?> run, TaskListener listener) {
        publish( run, listener);
    }

    private String[] getJobNamesToSkip() {
        String jobs = this.getDescriptorImpl().getJobsToSkip().trim();
        if (jobs.isEmpty()) {
            return new String[]{};
        }
        return jobs.split(" ", 0);
    }

    public Descriptor<GlobalSlackNotifier> getDescriptor() {
        return getDescriptorImpl();
      }

      public DescriptorImpl getDescriptorImpl() {
        return (DescriptorImpl) Jenkins.getInstance().getDescriptorOrDie(GlobalSlackNotifier.class);
      }

      public SlackNotifier.DescriptorImpl getSlackDescriptor(){
        return (SlackNotifier.DescriptorImpl) Jenkins.getInstance().getDescriptor(SlackNotifier.class);
      }

      public SlackMessage getSlackMessage(Result result){
          return getDescriptorImpl().getSlackMessage(result);
      }

      public void publish(Run<?, ?> r, TaskListener listener)
      {
          Result result = r.getResult();
          SlackMessage message = getSlackMessage(result);
          SlackNotifier.DescriptorImpl slackDesc = getSlackDescriptor();

          if(!message.getEnable()){ return; }

          String teamDomain = slackDesc.getTeamDomain();

          String baseUrl = slackDesc.getBaseUrl();


          String authToken = slackDesc.getToken();
          boolean botUser = slackDesc.isBotUser();

          String authTokenCredentialId = slackDesc.getTokenCredentialId();
          String sendAs = slackDesc.getSendAs();


          String room = message.getRoom();
          if (StringUtils.isEmpty(room)) {
              room = slackDesc.getRoom();
          }
          if(StringUtils.isEmpty(room)){ return; }

          EnvVars env = null;
          try {
              env = r.getEnvironment(listener);
          } catch (Exception e) {
              listener.getLogger().println("Error retrieving environment vars: " + e.getMessage());
              env = new EnvVars();
          }

          baseUrl = env.expand(baseUrl);
          teamDomain = env.expand(teamDomain);
          authToken = env.expand(authToken);
          authTokenCredentialId = env.expand(authTokenCredentialId);
          room = env.expand(room);

          String postText = env.expand(message.getMessage());


          CommitInfoChoice choice = CommitInfoChoice.forDisplayName("nothing about commits"); //TODO :selectable
          // imcompletely
          SlackNotifier notifier = new SlackNotifier(baseUrl,teamDomain,authToken,botUser,room,authTokenCredentialId,
            sendAs,false,true,true,
            true,true,true,true,true,
            true,false,false,
            choice,!StringUtils.isEmpty(postText),postText, null, null, null, null, null);
          String messageText = getBuildStatusMessage(r,notifier,false,false,!StringUtils.isEmpty(postText));

          // Skip sending message if the current job name is in the skip list.
          String[] jobNames = this.getJobNamesToSkip();
          if (jobNames.length > 0) {
              for (String name : jobNames) {
                  if (messageText.startsWith(name)) {
                      logger.info("Skip publishing Slack message for Job " + name);
                      return;
                  }
              }
          }

          SlackService service = new StandardSlackService(baseUrl, teamDomain, authToken, authTokenCredentialId, botUser, room);
          boolean postResult = service.publish(messageText, message.getColor());
          if(!postResult){
              StringBuilder s = new StringBuilder("Global Slack Notifier try posting to slack. However some error occurred\n");
              s.append("TeamDomain :" + teamDomain + "\n");
              s.append("Channel :" + room + "\n");
              s.append("Message :" + postText + "\n");

              listener.getLogger().println(s.toString());
          }

        }

        /**
         * Copy from Slack Plugin's ActiveNotifier.getBuildStatusMessage & I changed AbstractBuild to Run
         * https://github.com/jenkinsci/slack-plugin/blob/master/src/main/java/jenkins/plugins/slack/ActiveNotifier.java#L256
         */
        String getBuildStatusMessage(Run<?,?> r,SlackNotifier notifier, boolean includeTestSummary, boolean includeFailedTests, boolean includeCustomMessage) {
            MessageBuilder message = new MessageBuilder(notifier, r);
            message.appendStatusMessage();
            message.appendDuration();
            message.appendOpenLink();
            if (includeTestSummary) {
                message.appendTestSummary();
            }
            if (includeFailedTests) {
                message.appendFailedTests();
            }
            if (includeCustomMessage) {
                message.appendCustomMessage();
            }
            return message.toString();
        }


      @Extension @Symbol("globalSlackNotifier")
      public static final class DescriptorImpl extends Descriptor<GlobalSlackNotifier> {

        private String successRoom;
        private String successMessage;
        private boolean notifyOnSuccess;

        private String failureRoom;
        private String failureMessage;
        private boolean notifyOnFail;

        private String unstableRoom;
        private String unstableMessage;
        private boolean notifyOnUnstable;

        private String notBuiltRoom;
        private String notBuiltMessage;
        private boolean notifyOnNotBuilt;

        private String abortedRoom;
        private String abortedMessage;
        private boolean notifyOnAborted;

        private String jobsToSkip;

        public DescriptorImpl() {
            try{
                load();
            }catch(NullPointerException e)
            {

            }
        }
        public String getDisplayName() {
            return "Global Slack Messages";
        }

        public SlackMessage getSlackMessage(Result result){

            if(result == Result.SUCCESS)
            {
                return new SlackMessage(successRoom, successMessage, notifyOnSuccess, "good");
            }else if(result == Result.FAILURE){
                return new SlackMessage(failureRoom, failureMessage, notifyOnFail, "danger");
            }else if(result == Result.UNSTABLE){
                return new SlackMessage(unstableRoom, unstableMessage, notifyOnUnstable, "warning");
            }else if(result == Result.NOT_BUILT){
                return new SlackMessage(notBuiltRoom, notBuiltMessage, notifyOnNotBuilt, "gray");
            }else if(result == Result.ABORTED){
                return new SlackMessage(abortedRoom, abortedMessage, notifyOnAborted, "warning");
            }
            throw new IllegalArgumentException("result not match");
        }


        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) {
            req.bindJSON(this, formData);

            save();

            return true;
        }

		/**
		 * @return the jobsToSkip
		 */
		public String getJobsToSkip() {
			return jobsToSkip;
		}

		/**
		 * @return the successRoom
		 */
		public String getSuccessRoom() {
			return successRoom;
		}
		/**
		 * @return the successMessage
		 */
		public String getSuccessMessage() {
			return successMessage;
		}

		/**
		 * @return the notifyOnSuccess
		 */
		public boolean isNotifyOnSuccess() {
			return notifyOnSuccess;
		}

		/**
		 * @return the failureRoom
		 */
		public String getFailureRoom() {
			return failureRoom;
		}

		/**
		 * @return the failureMessage
		 */
		public String getFailureMessage() {
			return failureMessage;
		}

		/**
		 * @return the notifyOnFail
		 */
		public boolean isNotifyOnFail() {
			return notifyOnFail;
		}

		/**
		 * @return the unstableRoom
		 */
		public String getUnstableRoom() {
			return unstableRoom;
		}

		/**
		 * @return the unstableMessage
		 */
		public String getUnstableMessage() {
			return unstableMessage;
		}

		/**
		 * @return the notifyOnUnstable
		 */
		public boolean isNotifyOnUnstable() {
			return notifyOnUnstable;
		}

		/**
		 * @return the notBuiltRoom
		 */
		public String getNotBuiltRoom() {
			return notBuiltRoom;
		}

		/**
		 * @return the notBuiltMessage
		 */
		public String getNotBuiltMessage() {
			return notBuiltMessage;
		}

		/**
		 * @return the notifyOnNotBuilt
		 */
		public boolean isNotifyOnNotBuilt() {
			return notifyOnNotBuilt;
		}

		/**
		 * @return the abortedRoom
		 */
		public String getAbortedRoom() {
			return abortedRoom;
		}

		/**
		 * @return the abortedMessage
		 */
		public String getAbortedMessage() {
			return abortedMessage;
        }

		/**
		 * @return the notifyOnAborted
		 */
		public boolean isNotifyOnAborted() {
			return notifyOnAborted;
		}

		@DataBoundSetter
		public void setJobsToSkip(String jobsToSkip) {
			this.jobsToSkip = jobsToSkip;
		}

		@DataBoundSetter
        public void setSuccessMessage(String successMessage) { this.successMessage = successMessage; }

		@DataBoundSetter
        public void setSuccessRoom(String successRoom) { this.successRoom = successRoom; }

        @DataBoundSetter
        public void setNotifyOnSuccess(boolean notifyOnSuccess) { this.notifyOnSuccess = notifyOnSuccess; }



        @DataBoundSetter
        public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }

        @DataBoundSetter
        public void setFailureRoom(String failureRoom) { this.failureRoom = failureRoom; }

        @DataBoundSetter
        public void setNotifyOnFail(boolean notifyOnFail) { this.notifyOnFail = notifyOnFail; }



        @DataBoundSetter
        public void setUnstableMessage(String unstableMessage) { this.unstableMessage = unstableMessage; }

        @DataBoundSetter
        public void setUnstableRoom(String unstableRoom) { this.unstableRoom = unstableRoom; }

        @DataBoundSetter
        public void setNotifyOnUnstable(boolean notifyOnUnstable) { this.notifyOnUnstable = notifyOnUnstable; }



        @DataBoundSetter
        public void setNotBuiltMessage(String notBuiltMessage) { this.notBuiltMessage = notBuiltMessage; }

        @DataBoundSetter
        public void setNotBuiltRoom(String notBuiltRoom) { this.notBuiltRoom = notBuiltRoom; }

        @DataBoundSetter
        public void setNotifyOnNotBuilt(boolean notifyOnNotBuilt) { this.notifyOnNotBuilt = notifyOnNotBuilt; }



        @DataBoundSetter
        public void setAbortedMessage(String abortedMessage) { this.abortedMessage = abortedMessage; }

        @DataBoundSetter
        public void setAbortedRoom(String abortedRoom) { this.abortedRoom = abortedRoom; }

        @DataBoundSetter
        public void setNotifyOnAborted(boolean notifyOnAborted) { this.notifyOnAborted = notifyOnAborted; }
      }
}
