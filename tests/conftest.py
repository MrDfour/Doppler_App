"""Global fixtures for sandman_doppler integration."""

from importlib.util import find_spec


# The Home Assistant test harness is only needed by tests that import homeassistant.
#
# It is also unusable on Windows: `homeassistant/runner.py` imports `fcntl`, which is
# Unix-only, so the package cannot be imported there at all. The transport tests in
# tests/test_cloud.py, test_clock.py and test_discovery.py therefore load their modules
# directly by path and never touch it, which keeps them runnable on any platform.
#
# Registering the plugin unconditionally would abort collection for every test the
# moment it is absent *or* broken, so both states are handled here. Tests that need
# Home Assistant request the harness's own `enable_custom_integrations` fixture
# explicitly rather than having it forced on everything.
def _harness_usable() -> bool:
    """Return whether the Home Assistant pytest plugin can actually be imported."""
    if find_spec("pytest_homeassistant_custom_component") is None:
        return False
    try:
        # Probe the *plugin* module, which is what pytest loads. The bare top-level
        # package imports fine even where the plugin cannot.
        import pytest_homeassistant_custom_component.plugins  # noqa: F401
    except Exception:  # pragma: no cover - platform dependent
        return False
    return True


if _harness_usable():
    pytest_plugins = "pytest_homeassistant_custom_component"
