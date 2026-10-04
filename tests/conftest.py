"""Global fixtures for sandman_doppler integration."""

from importlib.util import find_spec

import pytest


# The Home Assistant test harness is only needed by tests that import homeassistant.
#
# It is also unusable on Windows: `homeassistant/runner.py` imports `fcntl`, which is
# Unix-only, so the package cannot be imported here at all. The transport tests in
# tests/test_cloud.py therefore load the module directly by path and never touch it.
#
# Registering the plugin unconditionally would abort collection for every test the
# moment it is absent *or* broken, which is why both states are handled here.
def _harness_usable() -> bool:
    if find_spec("pytest_homeassistant_custom_component") is None:
        return False
    try:
        # Probe the *plugin* module, which is what pytest actually loads. The bare
        # top-level package imports fine even where the plugin cannot: it is
        # .plugins -> patch_time -> homeassistant.runner -> the Unix-only fcntl.
        import pytest_homeassistant_custom_component.plugins  # noqa: F401
    except Exception:  # pragma: no cover - platform dependent
        return False
    return True


HAS_HA_HARNESS = _harness_usable()

if HAS_HA_HARNESS:
    pytest_plugins = "pytest_homeassistant_custom_component"


if HAS_HA_HARNESS:

    @pytest.fixture(autouse=True)
    def auto_enable_custom_integrations(enable_custom_integrations):
        """Load custom integrations for every test, as the HA harness requires."""
        yield


# Without a usable harness there is nothing to enable. Declaring the fixture anyway
# would make pytest fail with "fixture 'enable_custom_integrations' not found" for every
# test in the suite, including the ones that do not need Home Assistant.
