///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 21+

//DEPS org.kohsuke:github-api:2.0-rc.1
//DEPS one.util:streamex:0.8.3
//DEPS me.tongfei:progressbar:0.10.1
//DEPS org.jline:jline-terminal:3.29.0
//DEPS org.eclipse.collections:eclipse-collections:11.1.0
//DEPS info.picocli:picocli:4.7.6
//DEPS org.yaml:snakeyaml:2.4
//DEPS org.jooq:jool:0.9.15
//DEPS org.tinylog:tinylog-api:2.7.0
//DEPS org.tinylog:tinylog-impl:2.7.0
//DEPS org.tinylog:slf4j-tinylog:2.7.0 // because of jgit
//FILES tinylog.properties

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jooq.lambda.Unchecked;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestReviewEvent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHWorkflowJob;
import org.kohsuke.github.GHWorkflowRun;
import org.kohsuke.github.GitHub;
import org.tinylog.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "ghprcomment",
        version = "ghprcomment 2024-08-22",
        mixinStandardHelpOptions = true,
        sortSynopsis = false)
public class ghprcomment implements Callable<Integer> {

    public static final int REPOSITORY_REFERENCE_ERROR = 1;
    private static final int GH_PR_COMMENT_YAML_NOT_FOUND = 2;

    private final String CONFIG_FILE_NAME = "ghprcomment";

    private final String MAGIC_COMMENT = "<!-- created by ghprcomment -->";

    private final List<Path> SEARCH_PATHS = List.of(Path.of("."), Path.of(".github"));

    private final List<Path> CONFIG_PATHS = SEARCH_PATHS.stream()
                                                        .flatMap(path -> Stream.of(path.resolve(CONFIG_FILE_NAME + ".yaml"), path.resolve(CONFIG_FILE_NAME + ".yml")))
                                                        .toList();

    @Option(names = { "-r", "--repository" }, description = "The GitHub repository in the form owner/repository. E.g., JabRef/jabref", required = true)
    private String repository;

    @Option(names = { "-w", "--workflow-run-id" }, required = true)
    private Long workflowRunId;

    // PR_NUMBER: ${{ github.event.number }}
    @Option(names = { "-p", "--pr-number" }, required = true)
    private Integer pullRequestNumber;

    public static void main(String... args)  {
        CommandLine commandLine = new CommandLine(new ghprcomment());
        commandLine.parseArgs(args);
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        Optional<Path> configPath = CONFIG_PATHS.stream()
                                           .filter(Files::exists)
                                           .findFirst();
        if (configPath.isEmpty()) {
            Logger.error("{} not found. Searched at {}.", CONFIG_FILE_NAME, CONFIG_PATHS);
            return GH_PR_COMMENT_YAML_NOT_FOUND;
        }

        Logger.info("Connecting to {}...", repository);
        GitHub gitHub = GitHub.connect();

        GHRepository gitHubRepository;
        try {
            gitHubRepository = gitHub.getRepository(repository);

            // We fetch the pull request early to ensure that the number is valid
            Logger.debug("Pull Request number: {}", pullRequestNumber);
            GHPullRequest pullRequest = gitHubRepository.getPullRequest(pullRequestNumber);

            // Delete all previous comments
            pullRequest.getComments().forEach(Unchecked.consumer(comment -> {
                String body = comment.getBody();
                Logger.trace("Comment body: {}", body);
                if (body.contains(MAGIC_COMMENT)) {
                    Logger.debug("Found a match - deleting {}", comment.getId());
                    comment.delete();
                }
            }));

            GHWorkflowRun workflowRun = gitHubRepository.getWorkflowRun(workflowRunId);
            Logger.debug("workflowRunId: {}", workflowRunId);
            Set<String> failedJobs = workflowRun.listAllJobs().toList().stream()
                                                .filter(job -> job.getConclusion() == GHWorkflowRun.Conclusion.FAILURE)
                                                .map(GHWorkflowJob::getName)
                                                .collect(Collectors.toSet());
            Logger.debug("Failed jobs: {}", failedJobs);
            List<FailureComment> failureComments = getFailureComments(configPath.get());
            Optional<FailureComment> commentToPost = failureComments.stream()
                                                                    .filter(fc -> failedJobs.contains(fc.jobName))
                                                                    .findFirst();
            Logger.debug("Found comment: {}", commentToPost);
            if (commentToPost.isPresent()) {
                postComment(commentToPost.get().message, pullRequest);
            }
        } catch (IllegalArgumentException e) {
            Logger.error("Error in repository reference {}", repository);
            return REPOSITORY_REFERENCE_ERROR;
        }
        return 0;
    }

    private void postComment(String message, GHPullRequest pullRequest) throws Exception {
        Logger.trace("message: {}", message);
        String body = message + "\n\n" + MAGIC_COMMENT;
        Logger.trace("Creating PR comment...", body);
        pullRequest.comment(body);
    }

    private static List<FailureComment> getFailureComments(Path yamlFile) throws IOException {
        LoaderOptions options = new LoaderOptions();
        Yaml yaml = new Yaml(options);

        // SnakeYAML 2.2 cannot handle records (returns a LinkedList instead of record)
        // We do the conversion "by hand"
        List<Map<String, String>> failureComments;
        try (InputStream inputStream = Files.newInputStream(yamlFile)) {
            failureComments = yaml.load(inputStream);
        }
        Logger.trace("failureComments {}", failureComments);
        List<FailureComment> result = failureComments.stream().map(map -> new FailureComment(map.get("jobName"), map.get("message"))).toList();
        Logger.trace("result {}", result);
        return result;
    }

    record FailureComment(String jobName, String message) {}
}
