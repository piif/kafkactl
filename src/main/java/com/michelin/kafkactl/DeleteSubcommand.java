package com.michelin.kafkactl;

import com.michelin.kafkactl.models.ApiResource;
import com.michelin.kafkactl.models.ObjectMeta;
import com.michelin.kafkactl.models.Resource;
import com.michelin.kafkactl.parents.DryRunCommand;
import com.michelin.kafkactl.services.FileService;
import com.michelin.kafkactl.services.FormatService;
import com.michelin.kafkactl.services.ResourceService;
import com.michelin.kafkactl.utils.VersionProvider;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import jakarta.inject.Inject;
import java.io.File;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Delete subcommand.
 */
@Command(name = "delete",
    headerHeading = "@|bold Usage|@:",
    synopsisHeading = " ",
    descriptionHeading = "%n@|bold Description|@:%n%n",
    description = "Delete a resource.",
    parameterListHeading = "%n@|bold Parameters|@:%n",
    optionListHeading = "%n@|bold Options|@:%n",
    commandListHeading = "%n@|bold Commands|@:%n",
    usageHelpAutoWidth = true,
    versionProvider = VersionProvider.class,
    mixinStandardHelpOptions = true)
public class DeleteSubcommand extends DryRunCommand {
    @Inject
    public ResourceService resourceService;

    @Inject
    public FormatService formatService;

    @Inject
    public FileService fileService;

    @ArgGroup(multiplicity = "1")
    public EitherOf config;

    /**
     * Run the "delete" command.
     *
     * @return The command return code
     */
    @Override
    public Integer onAuthSuccess() {
        String namespace = kafkactlCommand.optionalNamespace.orElse(kafkactlConfig.getCurrentNamespace());
        List<Resource> resources = parseResources(namespace);

        try {
            resourceService.validateAllowedResources(resources, commandSpec);
            validateNamespace(resources);

            // Process each document individually, return 0 when all succeed
            int errors = resources.stream()
                .map(resource -> {
                    ApiResource apiResource =
                        apiResourcesService.getResourceDefinitionByKind(resource.getKind()).orElseThrow();
                    return resourceService.delete(apiResource, namespace, resource.getMetadata().getName(), dryRun,
                        commandSpec);
                })
                .mapToInt(value -> Boolean.TRUE.equals(value) ? 0 : 1)
                .sum();

            return errors > 0 ? 1 : 0;
        } catch (HttpClientResponseException e) {
            formatService.displayError(e, commandSpec);
            return 1;
        }
    }

    /**
     * Parse given resources.
     *
     * @param namespace The namespace
     * @return A list of resources
     */
    private List<Resource> parseResources(String namespace) {
        if (config.fileConfig != null && config.fileConfig.file.isPresent()) {
            // List all files to process
            List<File> yamlFiles =
                fileService.computeYamlFileList(config.fileConfig.file.get(), config.fileConfig.recursive);
            if (yamlFiles.isEmpty()) {
                throw new CommandLine.ParameterException(commandSpec.commandLine(),
                    "Could not find YAML or YML files in " + config.fileConfig.file.get().getName() + " directory.");
            }
            // Load each files
            return fileService.parseResourceListFromFiles(yamlFiles);
        }

        Optional<ApiResource> optionalApiResource =
            apiResourcesService.getResourceDefinitionByName(config.nameConfig.resourceType);
        if (optionalApiResource.isEmpty()) {
            throw new CommandLine.ParameterException(commandSpec.commandLine(),
                "The server does not have resource type(s) " + config.nameConfig.resourceType + ".");
        }
        // Generate a single resource with minimum details from input
        return List.of(Resource.builder()
            .metadata(ObjectMeta.builder()
                .name(config.nameConfig.name)
                .namespace(namespace)
                .build())
            .kind(optionalApiResource.get().getKind())
            .build());
    }

    static class EitherOf {
        @ArgGroup(exclusive = false)
        public ByName nameConfig;

        @ArgGroup(exclusive = false)
        public ByFile fileConfig;
    }

    static class ByName {
        @Parameters(index = "0", description = "Resource type.", arity = "1")
        public String resourceType;

        @Parameters(index = "1", description = "Resource name.", arity = "1")
        public String name;
    }

    static class ByFile {
        @Option(names = {"-f", "--file"}, description = "YAML file or directory containing resources.")
        public Optional<File> file;

        @Option(names = {"-R", "--recursive"}, description = "Search file recursively.")
        public boolean recursive;
    }
}
