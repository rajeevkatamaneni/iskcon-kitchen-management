package org.iskcon.kms.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Loads the recipe library at start-up, when told to.
 *
 * <p>Off by default. Set {@code kms.recipe-library.load-on-start=true} and the same image, deployed
 * as a Cloud Run job, becomes the loader — no second artifact, no second dependency set, no second
 * thing to keep in step with the schema.
 *
 * <p>A failure here stops the process rather than logging and carrying on. The load is the only
 * reason that deployment exists, and a job that exits zero having loaded nothing is a job somebody
 * believes succeeded.
 */
@Component
@ConditionalOnProperty(name = "kms.recipe-library.load-on-start", havingValue = "true")
public class LibraryLoadRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LibraryLoadRunner.class);

	private final LibraryLoader loader;

	public LibraryLoadRunner(LibraryLoader loader) {
		this.loader = loader;
	}

	@Override
	public void run(ApplicationArguments args) {
		log.info("Loading the recipe library on start-up");
		loader.load();
	}
}
