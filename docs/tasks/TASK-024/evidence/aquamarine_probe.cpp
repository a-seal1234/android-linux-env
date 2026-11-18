/* TASK-024: Aquamarine backend probe — reports exactly why CBackend::create/start fails. */
#include <aquamarine/backend/Backend.hpp>
#include <cstdio>
#include <cstring>
#include <vector>

static void logFn(Aquamarine::eBackendLogLevel level, const std::string& msg) {
	fprintf(stderr, "[AQ %d] %s\n", (int) level, msg.c_str());
}

int main(void) {
	setvbuf(stderr, nullptr, _IONBF, 0);
	setvbuf(stdout, nullptr, _IONBF, 0);

	Aquamarine::SBackendOptions options;
	options.logFunction = logFn;

	std::vector<Aquamarine::SBackendImplementationOptions> impls;
	Aquamarine::SBackendImplementationOptions o;
	o.backendType = Aquamarine::eBackendType::AQ_BACKEND_HEADLESS;
	o.backendRequestMode = Aquamarine::eBackendRequestMode::AQ_BACKEND_REQUEST_IF_AVAILABLE;
	impls.emplace_back(o);
	o.backendType = Aquamarine::eBackendType::AQ_BACKEND_WAYLAND;
	o.backendRequestMode = Aquamarine::eBackendRequestMode::AQ_BACKEND_REQUEST_MANDATORY;
	impls.emplace_back(o);

	auto backend = Aquamarine::CBackend::create(impls, options);
	printf("CBackend::create() = %p\n", (void*) backend.get());
	if (!backend) return 1;

	bool started = backend->start();
	printf("CBackend::start() = %d\n", (int) started);
	printf("AQ_PROBE_DONE\n");
	return started ? 0 : 2;
}
