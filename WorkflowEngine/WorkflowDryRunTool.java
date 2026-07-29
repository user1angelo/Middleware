import com.yourorg.workflow.*;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * WorkflowDryRunTool - offline, no-RabbitMQ-needed check for whether a sample alert JSON
 * matches any workflow YAML in a given directory.
 * <p>
 * This ships the "throwaway .java file" trick that was reinvented from scratch three separate
 * times during development of this SDK (each time a new data source's workflow needed checking
 * before a live run) as a permanent, supported tool - see SDK_USABILITY_AUDIT.md, Progressive
 * Evaluation dimension.
 * <p>
 * Build (same convention as the existing WorkflowTester.java in this directory):
 *   javac -cp "lib/*;target/classes" -d target/classes WorkflowDryRunTool.java
 * Run:
 *   java -cp "lib/*;target/classes" WorkflowDryRunTool <workflows-dir> <alert-json-file>
 * Example:
 *   java -cp "lib/*;target/classes" WorkflowDryRunTool workflows/ransomware sample_alerts/maltrail_ransomware_alert.json
 */
public class WorkflowDryRunTool {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Usage: java WorkflowDryRunTool <workflows-dir> <alert-json-file>");
            System.out.println("Example: java WorkflowDryRunTool workflows/ransomware sample_alerts/maltrail_ransomware_alert.json");
            System.exit(1);
        }

        String workflowsDir = args[0];
        String alertJsonPath = args[1];

        String alertJsonText = new String(Files.readAllBytes(Paths.get(alertJsonPath)));
        JSONObject alert = new JSONObject(alertJsonText);

        WorkflowLoader loader = new WorkflowLoader();
        WorkflowMatcher matcher = new WorkflowMatcher();

        List<Workflow> workflows = loader.loadWorkflows(workflowsDir);

        System.out.println("\n=== DRY RUN ===");
        System.out.println("Workflows directory: " + workflowsDir);
        System.out.println("Loaded " + workflows.size() + " workflow(s)");
        System.out.println("Alert file: " + alertJsonPath);
        System.out.println("Alert event_type: " + alert.optString("event_type", "(missing)"));
        if (alert.has("payload")) {
            System.out.println("Alert payload: " + alert.getJSONObject("payload").toString(2));
        }

        List<Workflow> matches = matcher.findMatchingWorkflows(alert, workflows);

        System.out.println("\n=== RESULT ===");
        if (matches.isEmpty()) {
            System.out.println("No workflows matched this alert.");
            System.out.println("(Check: does any workflow's trigger.event_type exactly equal \""
                    + alert.optString("event_type", "") + "\"? Does its condition's field names/"
                    + "operators/casing match the payload above? See WorkflowMatcher.java's "
                    + "evaluateCondition() for the exact substring-based grammar it supports.)");
        } else {
            System.out.println(matches.size() + " workflow(s) matched:");
            for (Workflow w : matches) {
                String eventType = w.getTrigger() != null ? w.getTrigger().getEventType() : "(none)";
                System.out.println("  - " + w.getName() + " (trigger event_type: " + eventType + ")");
            }
        }
    }
}
