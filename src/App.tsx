import React, { useState, useEffect, useMemo, useRef } from 'react';
import {
  Clock,
  Sparkles,
  Bell,
  Volume2,
  Sliders,
  Wifi,
  Sun,
  Moon,
  Smartphone,
  Send,
  Play,
  Square,
  RefreshCw,
  Plus,
  Trash2,
  Check,
  AlertCircle,
  Activity,
  Terminal,
  FileCode,
  Radio,
  Zap,
  Info
} from 'lucide-react';

interface RGBColor {
  r: number;
  g: number;
  b: number;
}

interface DopplerAlarm {
  id: number;
  name: string;
  time: string;
  repeat: string[];
  color: RGBColor;
  volume: number;
  status: 'set' | 'unarmed' | 'snoozed' | 'active';
  sound: string;
}

interface LightBarEffect {
  mode: 'off' | 'set' | 'set_each' | 'blink' | 'pulse' | 'comet' | 'sweep' | 'rainbow';
  color?: RGBColor;
  colors?: RGBColor[];
  rainbow?: boolean;
  speed?: number;
  duration?: number;
  sparkle?: 'low' | 'medium' | 'high' | null;
  gap?: number;
  size?: number;
  direction?: 'left' | 'right' | 'bounce';
  expiresAt?: number | null;
}

interface DopplerDevice {
  id: string;
  dsn: string;
  name: string;
  ipAddress: string;
  firmwareVersion: string;
  softwareVersion: string;
  modelNumber: string;
  manufacturer: string;
  online: boolean;
  uptimeSeconds: number;
  wifiSsid: string;
  wifiRssi: number;
  alexaLoggedIn: boolean;

  dayDisplayColor: RGBColor;
  dayDisplayBrightness: number;
  nightDisplayColor: RGBColor;
  nightDisplayBrightness: number;
  dayButtonColor: RGBColor;
  dayButtonBrightness: number;
  nightButtonColor: RGBColor;
  nightButtonBrightness: number;
  smartButtonColor: RGBColor;

  syncDisplayAndButtonDay: boolean;
  syncDisplayAndButtonNight: boolean;
  syncDayAndNight: boolean;

  ambientLightSensorLux: number;
  isNightMode: boolean;
  dayToNightThreshold: number;
  nightToDayThreshold: number;

  time24Hour: boolean;
  leadingZero24Hour: boolean;
  colonBlink: boolean;
  colonVisible: boolean;
  displaySecondsOnMini: boolean;
  fadeTimeMode: boolean;
  timeOffsetMinutes: number;
  timezone: string;

  masterVolume: number;
  soundPreset: 'Flat' | 'Rock' | 'Pop' | 'Jazz' | 'Talk' | 'Classical';
  volumeDependentEq: boolean;
  ascendingAlarms: boolean;
  alexaTapToTalkTone: boolean;
  alexaWakeWordTone: boolean;

  weatherLocation: string;
  currentTemperatureF: number;
  weatherCondition: string;

  miniDisplayOverride: {
    number: number;
    color: RGBColor;
    expiresAt: number | null;
  } | null;

  mainDisplayTextOverride: {
    text: string;
    speed: number;
    durationSeconds: number;
    color: RGBColor;
    expiresAt: number | null;
  } | null;

  lightBarEffect: LightBarEffect | null;

  rainbowMode: {
    enabled: boolean;
    speed: number;
    mode: 'day' | 'night' | 'both';
  };

  alarms: Record<number, DopplerAlarm>;
}

interface DopplerEvent {
  id: string;
  timestamp: string;
  type: string;
  deviceId: string;
  deviceName: string;
  title: string;
  data: Record<string, unknown>;
}

const ALARM_SOUND_OPTIONS = [
  '1980.mp3',
  'Alarming.mp3',
  'Classic.mp3',
  'Dance.mp3',
  'Dialup.mp3',
  'Gentle.mp3',
  'Harp.mp3',
  'Jazz.mp3',
  'Jungle.mp3',
  'Playful.mp3',
  'Purite.mp3',
  'Rainforest.mp3',
  'Ripples.mp3',
  'Rooster.mp3',
  'Sandman.mp3',
  'Stream.mp3',
  'Sub.mp3',
  'Trek.mp3',
  'Violin.mp3',
  'Waves.mp3'
];

const PRESET_COLORS: { name: string; rgb: RGBColor; hex: string }[] = [
  { name: 'Cyan', rgb: { r: 0, g: 220, b: 255 }, hex: '#00dcff' },
  { name: 'Amber', rgb: { r: 255, g: 150, b: 0 }, hex: '#ff9600' },
  { name: 'Deep Red', rgb: { r: 255, g: 40, b: 40 }, hex: '#ff2828' },
  { name: 'Emerald', rgb: { r: 16, g: 220, b: 120 }, hex: '#10dc78' },
  { name: 'Purple', rgb: { r: 180, g: 60, b: 255 }, hex: '#b43cff' },
  { name: 'Cool White', rgb: { r: 240, g: 245, b: 255 }, hex: '#f0f5ff' },
  { name: 'Warm White', rgb: { r: 255, g: 220, b: 160 }, hex: '#ffdcA0' },
];

export default function App() {
  const [devices, setDevices] = useState<DopplerDevice[]>([]);
  const [selectedDeviceId, setSelectedDeviceId] = useState<string>('');
  const [activeTab, setActiveTab] = useState<'clock' | 'lighting' | 'alarms' | 'audio' | 'services' | 'android' | 'events'>('clock');
  const [loading, setLoading] = useState(true);
  const [currentTime, setCurrentTime] = useState<Date>(new Date());
  const [colonVisibleState, setColonVisibleState] = useState(true);
  const [events, setEvents] = useState<DopplerEvent[]>([]);
  const [statusMessage, setStatusMessage] = useState<string | null>(null);

  // Form states
  const [scrollingText, setScrollingText] = useState('SANDMAN');
  const [scrollDuration, setScrollDuration] = useState(10);
  const [scrollSpeed, setScrollSpeed] = useState(50);
  const [miniNumber, setMiniNumber] = useState(99);
  const [miniDuration, setMiniDuration] = useState(15);
  
  // Light bar control form
  const [lbMode, setLbMode] = useState<'set' | 'blink' | 'pulse' | 'comet' | 'sweep'>('pulse');
  const [lbColor, setLbColor] = useState<string>('#00dcff');
  const [lbRainbow, setLbRainbow] = useState(false);
  const [lbDuration, setLbDuration] = useState(15);
  const [lbSpeed, setLbSpeed] = useState(50);
  const [lbSparkle, setLbSparkle] = useState<'low' | 'medium' | 'high' | ''>('');
  const [lbDirection, setLbDirection] = useState<'left' | 'right' | 'bounce'>('right');

  // New Alarm form
  const [newAlarmTime, setNewAlarmTime] = useState('07:00');
  const [newAlarmName, setNewAlarmName] = useState('Morning Alarm');
  const [newAlarmSound, setNewAlarmSound] = useState('Sandman.mp3');
  const [newAlarmVolume, setNewAlarmVolume] = useState(75);
  const [newAlarmColor, setNewAlarmColor] = useState('#ff9600');
  const [newAlarmDays, setNewAlarmDays] = useState<string[]>(['Mo', 'Tu', 'We', 'Th', 'Fr']);

  const activeDevice = useMemo(() => {
    return devices.find(d => d.id === selectedDeviceId) || devices[0];
  }, [devices, selectedDeviceId]);

  // Load devices and events
  const fetchDevices = async () => {
    try {
      const res = await fetch('/api/devices');
      if (res.ok) {
        const data = await res.json();
        setDevices(data);
        if (!selectedDeviceId && data.length > 0) {
          setSelectedDeviceId(data[0].id);
        }
      }
    } catch (e) {
      console.error('Failed to fetch devices:', e);
    } finally {
      setLoading(false);
    }
  };

  const fetchEvents = async () => {
    try {
      const res = await fetch('/api/events');
      if (res.ok) {
        const data = await res.json();
        setEvents(data);
      }
    } catch (e) {
      console.error('Failed to fetch events:', e);
    }
  };

  useEffect(() => {
    fetchDevices();
    fetchEvents();
    const interval = setInterval(() => {
      fetchDevices();
      fetchEvents();
    }, 4000);
    return () => clearInterval(interval);
  }, []);

  // Clock tick
  useEffect(() => {
    const timer = setInterval(() => {
      setCurrentTime(new Date());
      setColonVisibleState(prev => !prev);
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  const updateDevice = async (patch: Partial<DopplerDevice>) => {
    if (!activeDevice) return;
    try {
      const res = await fetch(`/api/devices/${activeDevice.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(patch)
      });
      if (res.ok) {
        const updated = await res.json();
        setDevices(prev => prev.map(d => d.id === updated.id ? updated : d));
        showNotification('Settings saved to device');
      }
    } catch (e) {
      console.error('Error updating device:', e);
    }
  };

  const callService = async (serviceName: string, payload: Record<string, unknown>) => {
    if (!activeDevice) return;
    try {
      const res = await fetch(`/api/devices/${activeDevice.id}/services/${serviceName}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      if (res.ok) {
        showNotification(`Executed service: ${serviceName}`);
        fetchDevices();
        fetchEvents();
      }
    } catch (e) {
      console.error(`Error calling service ${serviceName}:`, e);
    }
  };

  const pressPhysicalButton = async (button: 1 | 2 | 'alarm' | 'snooze' | 'vol_up' | 'vol_down') => {
    if (!activeDevice) return;
    try {
      const res = await fetch(`/api/devices/${activeDevice.id}/button-press`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ button })
      });
      if (res.ok) {
        showNotification(`Button pressed: ${button}`);
        fetchDevices();
        fetchEvents();
      }
    } catch (e) {
      console.error('Error pressing button:', e);
    }
  };

  const showNotification = (msg: string) => {
    setStatusMessage(msg);
    setTimeout(() => setStatusMessage(null), 3500);
  };

  // Convert hex to rgb
  const hexToRgb = (hex: string): RGBColor => {
    const cleaned = hex.replace('#', '');
    const num = parseInt(cleaned, 16);
    return {
      r: (num >> 16) & 255,
      g: (num >> 8) & 255,
      b: num & 255
    };
  };

  const rgbToCss = (c?: RGBColor, alpha = 1) => {
    if (!c) return `rgba(255, 255, 255, ${alpha})`;
    return `rgba(${c.r}, ${c.g}, ${c.b}, ${alpha})`;
  };

  // Display color based on day/night mode
  const activeDisplayColor = useMemo(() => {
    if (!activeDevice) return { r: 0, g: 220, b: 255 };
    if (activeDevice.mainDisplayTextOverride && (!activeDevice.mainDisplayTextOverride.expiresAt || activeDevice.mainDisplayTextOverride.expiresAt > Date.now())) {
      return activeDevice.mainDisplayTextOverride.color;
    }
    return activeDevice.isNightMode ? activeDevice.nightDisplayColor : activeDevice.dayDisplayColor;
  }, [activeDevice]);

  const activeDisplayBrightness = useMemo(() => {
    if (!activeDevice) return 80;
    return activeDevice.isNightMode ? activeDevice.nightDisplayBrightness : activeDevice.dayDisplayBrightness;
  }, [activeDevice]);

  // Formatted digital time string
  const clockDigits = useMemo(() => {
    let hours = currentTime.getHours();
    const minutes = currentTime.getMinutes();
    const seconds = currentTime.getSeconds();
    let ampm = '';

    if (!activeDevice?.time24Hour) {
      ampm = hours >= 12 ? 'PM' : 'AM';
      hours = hours % 12;
      hours = hours ? hours : 12;
    }

    const hStr = activeDevice?.leadingZero24Hour || (!activeDevice?.time24Hour && hours >= 10)
      ? String(hours).padStart(2, '0')
      : String(hours);
    const mStr = String(minutes).padStart(2, '0');
    const sStr = String(seconds).padStart(2, '0');

    return { hStr, mStr, sStr, ampm };
  }, [currentTime, activeDevice]);

  // Check if colon should show
  const showColon = useMemo(() => {
    if (!activeDevice?.colonVisible) return false;
    if (activeDevice?.colonBlink) return colonVisibleState;
    return true;
  }, [activeDevice, colonVisibleState]);

  // Check if any alarm is ringing
  const activeAlarm = useMemo(() => {
    if (!activeDevice) return null;
    return Object.values(activeDevice.alarms).find(a => a.status === 'active');
  }, [activeDevice]);

  if (loading && devices.length === 0) {
    return (
      <div className="flex h-screen items-center justify-center bg-slate-950 text-slate-300">
        <div className="flex flex-col items-center gap-4">
          <RefreshCw className="h-8 w-8 animate-spin text-cyan-400" />
          <p className="text-sm font-medium tracking-wide">Connecting to Sandman Doppler...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col font-sans">
      {/* Top Header */}
      <header className="border-b border-slate-800 bg-slate-900/60 backdrop-blur-md px-6 py-4 sticky top-0 z-40 flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-cyan-500 to-blue-600 flex items-center justify-center shadow-lg shadow-cyan-500/20">
            <Clock className="w-6 h-6 text-white" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="font-bold text-lg tracking-tight text-white">Sandman Doppler</h1>
              <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-cyan-950 text-cyan-400 border border-cyan-800/60">
                Local Control
              </span>
            </div>
            <p className="text-xs text-slate-400">Standalone Smart Clock API &amp; Native Android Architecture</p>
          </div>
        </div>

        {/* Device Selector & Quick Info */}
        <div className="flex items-center gap-3 flex-wrap">
          <div className="flex items-center gap-2 bg-slate-800/80 px-3 py-1.5 rounded-lg border border-slate-700/60 text-xs">
            <Radio className="w-3.5 h-3.5 text-emerald-400 animate-pulse" />
            <span className="text-slate-400">Device:</span>
            <select
              value={selectedDeviceId}
              onChange={(e) => setSelectedDeviceId(e.target.value)}
              className="bg-transparent text-white font-medium focus:outline-none cursor-pointer"
            >
              {devices.map(d => (
                <option key={d.id} value={d.id} className="bg-slate-900 text-slate-200">
                  {d.name} ({d.ipAddress})
                </option>
              ))}
            </select>
          </div>

          <button
            onClick={() => fetchDevices()}
            className="p-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 transition-colors border border-slate-700/60"
            title="Refresh state"
          >
            <RefreshCw className="w-4 h-4" />
          </button>
        </div>
      </header>

      {/* Status toast notification */}
      {statusMessage && (
        <div className="fixed bottom-6 right-6 z-50 bg-cyan-900/90 text-cyan-100 border border-cyan-700 px-4 py-2.5 rounded-xl shadow-2xl flex items-center gap-2.5 text-sm backdrop-blur-md animate-in fade-in slide-in-from-bottom-2">
          <Check className="w-4 h-4 text-cyan-300" />
          <span>{statusMessage}</span>
        </div>
      )}

      {/* Main Content Area */}
      <div className="flex-1 max-w-7xl w-full mx-auto p-4 sm:p-6 grid grid-cols-1 lg:grid-cols-12 gap-6">
        
        {/* Left Column: Simulated Doppler Hardware Unit */}
        <div className="lg:col-span-5 flex flex-col gap-6">
          <div className="bg-slate-900/80 border border-slate-800 rounded-3xl p-6 shadow-2xl relative overflow-hidden flex flex-col items-center">
            
            {/* Top Status Bar of Hardware */}
            <div className="w-full flex items-center justify-between text-xs text-slate-400 mb-4 px-2">
              <div className="flex items-center gap-2">
                <span className={`w-2 h-2 rounded-full ${activeDevice?.online ? 'bg-emerald-400' : 'bg-rose-500'}`} />
                <span>{activeDevice?.online ? 'Local Wi-Fi Connected' : 'Offline'}</span>
              </div>
              <div className="flex items-center gap-3">
                <span className="flex items-center gap-1">
                  <Wifi className="w-3.5 h-3.5 text-cyan-400" />
                  {activeDevice?.wifiRssi} dBm
                </span>
                <span className="flex items-center gap-1">
                  {activeDevice?.isNightMode ? (
                    <Moon className="w-3.5 h-3.5 text-rose-400" />
                  ) : (
                    <Sun className="w-3.5 h-3.5 text-amber-400" />
                  )}
                  {activeDevice?.isNightMode ? 'Night' : 'Day'}
                </span>
              </div>
            </div>

            {/* Smart Hardware Buttons on top edge */}
            <div className="w-full flex justify-between items-center px-4 mb-4 gap-2">
              <div className="flex items-center gap-2">
                <button
                  onClick={() => pressPhysicalButton(1)}
                  className="px-3 py-1.5 rounded-xl bg-slate-800 hover:bg-slate-700 active:scale-95 text-xs font-semibold text-slate-200 border border-slate-700 flex items-center gap-1.5 shadow-sm transition-all"
                  style={{ borderColor: rgbToCss(activeDevice?.smartButtonColor, 0.4) }}
                >
                  <Zap className="w-3.5 h-3.5 text-emerald-400" />
                  Button 1
                </button>
                <button
                  onClick={() => pressPhysicalButton(2)}
                  className="px-3 py-1.5 rounded-xl bg-slate-800 hover:bg-slate-700 active:scale-95 text-xs font-semibold text-slate-200 border border-slate-700 flex items-center gap-1.5 shadow-sm transition-all"
                  style={{ borderColor: rgbToCss(activeDevice?.smartButtonColor, 0.4) }}
                >
                  <Zap className="w-3.5 h-3.5 text-cyan-400" />
                  Button 2
                </button>
              </div>

              <div className="flex items-center gap-2">
                <button
                  onClick={() => pressPhysicalButton('alarm')}
                  className={`px-3 py-1.5 rounded-xl text-xs font-semibold border transition-all active:scale-95 flex items-center gap-1.5 ${
                    activeAlarm ? 'bg-rose-600 border-rose-400 text-white animate-bounce' : 'bg-slate-800 border-slate-700 text-slate-300 hover:bg-slate-700'
                  }`}
                >
                  <Bell className="w-3.5 h-3.5 text-amber-300" />
                  Alarm
                </button>
                <button
                  onClick={() => pressPhysicalButton('snooze')}
                  className="px-3 py-1.5 rounded-xl bg-slate-800 hover:bg-slate-700 active:scale-95 text-xs font-semibold text-slate-200 border border-slate-700 transition-all"
                >
                  Snooze
                </button>
              </div>
            </div>

            {/* 29-LED Light Bar on top edge */}
            <div className="w-full bg-slate-950 p-2.5 rounded-2xl border border-slate-800 mb-6 shadow-inner">
              <div className="flex justify-between items-center gap-1 px-1">
                {Array.from({ length: 29 }).map((_, idx) => {
                  let ledColor = 'rgb(30, 41, 59)';
                  let glow = 'none';

                  const effect = activeDevice?.lightBarEffect;
                  if (effect && effect.mode !== 'off') {
                    if (effect.rainbow) {
                      const hue = (idx * (360 / 29) + (Date.now() / 20) % 360) % 360;
                      ledColor = `hsl(${hue}, 100%, 55%)`;
                      glow = `0 0 8px hsl(${hue}, 100%, 55%)`;
                    } else if (effect.colors && effect.colors.length > 0) {
                      const colorIndex = (idx + Math.floor(Date.now() / 400)) % effect.colors.length;
                      const c = effect.colors[colorIndex];
                      ledColor = rgbToCss(c);
                      glow = `0 0 8px ${rgbToCss(c, 0.8)}`;
                    } else if (effect.color) {
                      ledColor = rgbToCss(effect.color);
                      glow = `0 0 8px ${rgbToCss(effect.color, 0.8)}`;
                    } else {
                      ledColor = 'rgb(0, 220, 255)';
                      glow = '0 0 8px rgba(0, 220, 255, 0.8)';
                    }
                  } else if (activeAlarm) {
                    ledColor = 'rgb(244, 63, 94)';
                    glow = '0 0 10px rgba(244, 63, 94, 0.9)';
                  }

                  return (
                    <div
                      key={idx}
                      className="w-2.5 h-3 rounded-sm transition-all duration-200"
                      style={{
                        backgroundColor: ledColor,
                        boxShadow: glow
                      }}
                      title={`LED #${idx + 1}`}
                    />
                  );
                })}
              </div>
            </div>

            {/* Physical Doppler Face Display Frame */}
            <div className="w-full bg-black/95 rounded-2xl p-6 border-2 border-slate-800 shadow-[0_0_50px_rgba(0,0,0,0.8)] relative flex flex-col items-center justify-center min-h-[220px]">
              
              {/* Active Alarm Banner */}
              {activeAlarm && (
                <div className="absolute top-2 w-full text-center text-xs font-bold text-rose-400 animate-pulse tracking-wider uppercase">
                  🔔 ALARM RINGING: {activeAlarm.name} ({activeAlarm.sound})
                </div>
              )}

              {/* Main Digital Clock Display / Scrolling Text */}
              <div
                className="font-clock text-6xl sm:text-7xl font-bold tracking-wider select-none text-center transition-all duration-300"
                style={{
                  color: rgbToCss(activeDisplayColor),
                  opacity: (activeDisplayBrightness / 100).toFixed(2),
                  textShadow: `0 0 20px ${rgbToCss(activeDisplayColor, 0.6)}`
                }}
              >
                {activeDevice?.mainDisplayTextOverride && (!activeDevice.mainDisplayTextOverride.expiresAt || activeDevice.mainDisplayTextOverride.expiresAt > Date.now()) ? (
                  <div className="text-3xl sm:text-4xl animate-pulse font-mono tracking-widest uppercase">
                    {activeDevice.mainDisplayTextOverride.text}
                  </div>
                ) : (
                  <div className="flex items-baseline justify-center">
                    <span>{clockDigits.hStr}</span>
                    <span className={`mx-1 transition-opacity ${showColon ? 'opacity-100' : 'opacity-0'}`}>:</span>
                    <span>{clockDigits.mStr}</span>
                    {clockDigits.ampm && (
                      <span className="text-xl ml-2 text-slate-400 font-sans tracking-normal font-semibold">
                        {clockDigits.ampm}
                      </span>
                    )}
                  </div>
                )}
              </div>

              {/* Mini Display Section (Right Bottom / Auxiliary) */}
              <div className="w-full flex justify-between items-end mt-4 pt-4 border-t border-slate-900 text-xs">
                <div className="text-slate-500 font-mono">
                  {activeDevice?.weatherLocation}
                </div>

                <div
                  className="px-3 py-1 rounded bg-slate-900 border border-slate-800 font-mono font-bold text-sm tracking-widest"
                  style={{
                    color: activeDevice?.miniDisplayOverride?.color
                      ? rgbToCss(activeDevice.miniDisplayOverride.color)
                      : rgbToCss(activeDisplayColor),
                    textShadow: `0 0 10px ${rgbToCss(activeDisplayColor, 0.4)}`
                  }}
                >
                  {activeDevice?.miniDisplayOverride && (!activeDevice.miniDisplayOverride.expiresAt || activeDevice.miniDisplayOverride.expiresAt > Date.now()) ? (
                    <span>{activeDevice.miniDisplayOverride.number}</span>
                  ) : activeDevice?.displaySecondsOnMini ? (
                    <span>:{clockDigits.sStr}s</span>
                  ) : (
                    <span>{activeDevice?.currentTemperatureF}°F</span>
                  )}
                </div>
              </div>
            </div>

            {/* Ambient Lux Sensor Interactive Slider */}
            <div className="w-full mt-6 bg-slate-950/70 p-4 rounded-2xl border border-slate-800/80">
              <div className="flex justify-between items-center text-xs mb-2">
                <span className="text-slate-300 font-medium flex items-center gap-1.5">
                  <Sun className="w-3.5 h-3.5 text-amber-400" />
                  Ambient Light Sensor (Lux)
                </span>
                <span className="font-mono text-cyan-400 font-semibold">{activeDevice?.ambientLightSensorLux} lux</span>
              </div>
              <input
                type="range"
                min="0"
                max="500"
                value={activeDevice?.ambientLightSensorLux ?? 100}
                onChange={(e) => updateDevice({ ambientLightSensorLux: parseInt(e.target.value, 10) })}
                className="w-full accent-cyan-400 cursor-pointer h-1.5 bg-slate-800 rounded-lg"
              />
              <div className="flex justify-between text-[10px] text-slate-500 mt-1 font-mono">
                <span>0 lx (Pitch Dark)</span>
                <span>Threshold: {activeDevice?.dayToNightThreshold} / {activeDevice?.nightToDayThreshold} lx</span>
                <span>500 lx (Bright)</span>
              </div>
            </div>

            {/* Quick Volume Control */}
            <div className="w-full mt-3 bg-slate-950/70 p-4 rounded-2xl border border-slate-800/80">
              <div className="flex justify-between items-center text-xs mb-2">
                <span className="text-slate-300 font-medium flex items-center gap-1.5">
                  <Volume2 className="w-3.5 h-3.5 text-purple-400" />
                  Speaker Master Volume
                </span>
                <span className="font-mono text-purple-300 font-semibold">{activeDevice?.masterVolume}%</span>
              </div>
              <input
                type="range"
                min="0"
                max="100"
                value={activeDevice?.masterVolume ?? 70}
                onChange={(e) => updateDevice({ masterVolume: parseInt(e.target.value, 10) })}
                className="w-full accent-purple-400 cursor-pointer h-1.5 bg-slate-800 rounded-lg"
              />
            </div>
          </div>
        </div>

        {/* Right Column: Tabbed Control Panels */}
        <div className="lg:col-span-7 flex flex-col gap-4">
          
          {/* Tabs Navigation */}
          <div className="flex items-center gap-1 bg-slate-900 p-1.5 rounded-2xl border border-slate-800 overflow-x-auto text-xs font-semibold">
            <button
              onClick={() => setActiveTab('clock')}
              className={`px-3.5 py-2 rounded-xl transition-all flex items-center gap-1.5 whitespace-nowrap ${
                activeTab === 'clock' ? 'bg-cyan-500 text-slate-950 font-bold shadow-md shadow-cyan-500/20' : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
              }`}
            >
              <Clock className="w-3.5 h-3.5" />
              Clock Settings
            </button>
            <button
              onClick={() => setActiveTab('lighting')}
              className={`px-3.5 py-2 rounded-xl transition-all flex items-center gap-1.5 whitespace-nowrap ${
                activeTab === 'lighting' ? 'bg-cyan-500 text-slate-950 font-bold shadow-md shadow-cyan-500/20' : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
              }`}
            >
              <Sparkles className="w-3.5 h-3.5" />
              Lighting &amp; Lightbar
            </button>
            <button
              onClick={() => setActiveTab('alarms')}
              className={`px-3.5 py-2 rounded-xl transition-all flex items-center gap-1.5 whitespace-nowrap ${
                activeTab === 'alarms' ? 'bg-cyan-500 text-slate-950 font-bold shadow-md shadow-cyan-500/20' : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
              }`}
            >
              <Bell className="w-3.5 h-3.5" />
              Alarms
            </button>
            <button
              onClick={() => setActiveTab('audio')}
              className={`px-3.5 py-2 rounded-xl transition-all flex items-center gap-1.5 whitespace-nowrap ${
                activeTab === 'audio' ? 'bg-cyan-500 text-slate-950 font-bold shadow-md shadow-cyan-500/20' : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
              }`}
            >
              <Volume2 className="w-3.5 h-3.5" />
              Audio &amp; EQ
            </button>
            <button
              onClick={() => setActiveTab('services')}
              className={`px-3.5 py-2 rounded-xl transition-all flex items-center gap-1.5 whitespace-nowrap ${
                activeTab === 'services' ? 'bg-cyan-500 text-slate-950 font-bold shadow-md shadow-cyan-500/20' : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
              }`}
            >
              <Sliders className="w-3.5 h-3.5" />
              Services API
            </button>
            <button
              onClick={() => setActiveTab('android')}
              className={`px-3.5 py-2 rounded-xl transition-all flex items-center gap-1.5 whitespace-nowrap ${
                activeTab === 'android' ? 'bg-emerald-400 text-slate-950 font-bold shadow-md shadow-emerald-400/20' : 'text-emerald-400 hover:text-emerald-300 hover:bg-emerald-950/40'
              }`}
            >
              <Smartphone className="w-3.5 h-3.5" />
              Native Android App
            </button>
            <button
              onClick={() => setActiveTab('events')}
              className={`px-3.5 py-2 rounded-xl transition-all flex items-center gap-1.5 whitespace-nowrap ${
                activeTab === 'events' ? 'bg-cyan-500 text-slate-950 font-bold shadow-md shadow-cyan-500/20' : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
              }`}
            >
              <Activity className="w-3.5 h-3.5" />
              Log ({events.length})
            </button>
          </div>

          {/* TAB 1: CLOCK SETTINGS */}
          {activeTab === 'clock' && (
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col gap-6 shadow-xl animate-in fade-in">
              <h2 className="text-base font-bold text-white flex items-center gap-2">
                <Clock className="w-4 h-4 text-cyan-400" />
                Clock Display &amp; Time Preferences
              </h2>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div className="flex items-center justify-between p-3.5 rounded-xl bg-slate-950/70 border border-slate-800">
                  <div>
                    <div className="text-sm font-semibold text-slate-200">24-Hour Time Format</div>
                    <div className="text-xs text-slate-400">Toggle between 12h (AM/PM) and 24h</div>
                  </div>
                  <input
                    type="checkbox"
                    checked={activeDevice?.time24Hour ?? false}
                    onChange={(e) => updateDevice({ time24Hour: e.target.checked })}
                    className="w-5 h-5 accent-cyan-400 rounded cursor-pointer"
                  />
                </div>

                <div className="flex items-center justify-between p-3.5 rounded-xl bg-slate-950/70 border border-slate-800">
                  <div>
                    <div className="text-sm font-semibold text-slate-200">Leading Zero in 24h</div>
                    <div className="text-xs text-slate-400">Display "07:00" instead of "7:00"</div>
                  </div>
                  <input
                    type="checkbox"
                    checked={activeDevice?.leadingZero24Hour ?? false}
                    onChange={(e) => updateDevice({ leadingZero24Hour: e.target.checked })}
                    className="w-5 h-5 accent-cyan-400 rounded cursor-pointer"
                  />
                </div>

                <div className="flex items-center justify-between p-3.5 rounded-xl bg-slate-950/70 border border-slate-800">
                  <div>
                    <div className="text-sm font-semibold text-slate-200">Colon Blinking</div>
                    <div className="text-xs text-slate-400">Flash the colon dots once per second</div>
                  </div>
                  <input
                    type="checkbox"
                    checked={activeDevice?.colonBlink ?? true}
                    onChange={(e) => updateDevice({ colonBlink: e.target.checked })}
                    className="w-5 h-5 accent-cyan-400 rounded cursor-pointer"
                  />
                </div>

                <div className="flex items-center justify-between p-3.5 rounded-xl bg-slate-950/70 border border-slate-800">
                  <div>
                    <div className="text-sm font-semibold text-slate-200">Show Colon</div>
                    <div className="text-xs text-slate-400">Show or hide the colon completely</div>
                  </div>
                  <input
                    type="checkbox"
                    checked={activeDevice?.colonVisible ?? true}
                    onChange={(e) => updateDevice({ colonVisible: e.target.checked })}
                    className="w-5 h-5 accent-cyan-400 rounded cursor-pointer"
                  />
                </div>

                <div className="flex items-center justify-between p-3.5 rounded-xl bg-slate-950/70 border border-slate-800">
                  <div>
                    <div className="text-sm font-semibold text-slate-200">Display Seconds on Mini</div>
                    <div className="text-xs text-slate-400">Show live seconds on mini display</div>
                  </div>
                  <input
                    type="checkbox"
                    checked={activeDevice?.displaySecondsOnMini ?? false}
                    onChange={(e) => updateDevice({ displaySecondsOnMini: e.target.checked })}
                    className="w-5 h-5 accent-cyan-400 rounded cursor-pointer"
                  />
                </div>

                <div className="flex items-center justify-between p-3.5 rounded-xl bg-slate-950/70 border border-slate-800">
                  <div>
                    <div className="text-sm font-semibold text-slate-200">Fade Time Transition</div>
                    <div className="text-xs text-slate-400">Smooth cross-fade between minute changes</div>
                  </div>
                  <input
                    type="checkbox"
                    checked={activeDevice?.fadeTimeMode ?? true}
                    onChange={(e) => updateDevice({ fadeTimeMode: e.target.checked })}
                    className="w-5 h-5 accent-cyan-400 rounded cursor-pointer"
                  />
                </div>
              </div>

              {/* Weather Location */}
              <div className="p-4 rounded-xl bg-slate-950/70 border border-slate-800 flex flex-col gap-3">
                <div className="text-sm font-semibold text-slate-200">Weather &amp; Location</div>
                <div className="flex gap-2">
                  <input
                    type="text"
                    defaultValue={activeDevice?.weatherLocation}
                    placeholder="Zipcode or City, Country"
                    id="weather-loc-input"
                    className="flex-1 bg-slate-900 border border-slate-700 rounded-xl px-3.5 py-2 text-sm text-white focus:outline-none focus:border-cyan-400"
                  />
                  <button
                    onClick={() => {
                      const el = document.getElementById('weather-loc-input') as HTMLInputElement;
                      if (el && el.value) {
                        callService('set_weather_location', { location: el.value });
                      }
                    }}
                    className="px-4 py-2 bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-bold rounded-xl text-sm transition-colors"
                  >
                    Save Location
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* TAB 2: LIGHTING & LIGHTBAR */}
          {activeTab === 'lighting' && (
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col gap-6 shadow-xl animate-in fade-in">
              <div className="flex justify-between items-center">
                <h2 className="text-base font-bold text-white flex items-center gap-2">
                  <Sparkles className="w-4 h-4 text-cyan-400" />
                  Day &amp; Night Colors &amp; 29-LED Lightbar
                </h2>
              </div>

              {/* Color Controls */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                {/* Day Mode Colors */}
                <div className="p-4 rounded-xl bg-slate-950/70 border border-slate-800 flex flex-col gap-3">
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-semibold text-amber-300 flex items-center gap-1.5">
                      <Sun className="w-4 h-4" /> Day Display
                    </span>
                    <span className="text-xs font-mono text-slate-400">{activeDevice?.dayDisplayBrightness}%</span>
                  </div>
                  
                  <div className="flex items-center gap-2">
                    {PRESET_COLORS.map(c => (
                      <button
                        key={c.name}
                        onClick={() => updateDevice({ dayDisplayColor: c.rgb })}
                        className="w-7 h-7 rounded-full border border-slate-700 hover:scale-110 transition-transform"
                        style={{ backgroundColor: c.hex }}
                        title={c.name}
                      />
                    ))}
                  </div>

                  <input
                    type="range"
                    min="1"
                    max="100"
                    value={activeDevice?.dayDisplayBrightness ?? 85}
                    onChange={(e) => updateDevice({ dayDisplayBrightness: parseInt(e.target.value, 10) })}
                    className="w-full accent-amber-400 h-1.5 bg-slate-800 rounded-lg cursor-pointer mt-2"
                  />
                </div>

                {/* Night Mode Colors */}
                <div className="p-4 rounded-xl bg-slate-950/70 border border-slate-800 flex flex-col gap-3">
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-semibold text-rose-300 flex items-center gap-1.5">
                      <Moon className="w-4 h-4" /> Night Display
                    </span>
                    <span className="text-xs font-mono text-slate-400">{activeDevice?.nightDisplayBrightness}%</span>
                  </div>
                  
                  <div className="flex items-center gap-2">
                    {PRESET_COLORS.map(c => (
                      <button
                        key={c.name}
                        onClick={() => updateDevice({ nightDisplayColor: c.rgb })}
                        className="w-7 h-7 rounded-full border border-slate-700 hover:scale-110 transition-transform"
                        style={{ backgroundColor: c.hex }}
                        title={c.name}
                      />
                    ))}
                  </div>

                  <input
                    type="range"
                    min="1"
                    max="100"
                    value={activeDevice?.nightDisplayBrightness ?? 25}
                    onChange={(e) => updateDevice({ nightDisplayBrightness: parseInt(e.target.value, 10) })}
                    className="w-full accent-rose-400 h-1.5 bg-slate-800 rounded-lg cursor-pointer mt-2"
                  />
                </div>
              </div>

              {/* Light Bar Effect Generator */}
              <div className="p-5 rounded-xl bg-slate-950/90 border border-slate-800 flex flex-col gap-4">
                <div className="flex justify-between items-center">
                  <div className="text-sm font-bold text-white flex items-center gap-2">
                    <Sparkles className="w-4 h-4 text-cyan-400" />
                    Activate Lightbar Effect (29-LEDs)
                  </div>
                  {activeDevice?.lightBarEffect && (
                    <button
                      onClick={() => callService('stop_light_bar', {})}
                      className="text-xs text-rose-400 hover:text-rose-300 flex items-center gap-1 font-semibold"
                    >
                      <Square className="w-3.5 h-3.5" /> Stop Effect
                    </button>
                  )}
                </div>

                <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs">
                  <div>
                    <label className="text-slate-400 mb-1 block">Mode</label>
                    <select
                      value={lbMode}
                      onChange={(e) => setLbMode(e.target.value as any)}
                      className="w-full bg-slate-900 border border-slate-700 rounded-lg px-2.5 py-2 text-white"
                    >
                      <option value="pulse">Pulse</option>
                      <option value="comet">Comet</option>
                      <option value="blink">Blink</option>
                      <option value="sweep">Sweep</option>
                      <option value="set">Set Static</option>
                    </select>
                  </div>

                  <div>
                    <label className="text-slate-400 mb-1 block">Color</label>
                    <input
                      type="color"
                      value={lbColor}
                      onChange={(e) => setLbColor(e.target.value)}
                      className="w-full h-9 bg-slate-900 border border-slate-700 rounded-lg p-1 cursor-pointer"
                    />
                  </div>

                  <div>
                    <label className="text-slate-400 mb-1 block">Duration (sec)</label>
                    <input
                      type="number"
                      min="1"
                      max="300"
                      value={lbDuration}
                      onChange={(e) => setLbDuration(parseInt(e.target.value, 10))}
                      className="w-full bg-slate-900 border border-slate-700 rounded-lg px-2.5 py-2 text-white"
                    />
                  </div>

                  <div>
                    <label className="text-slate-400 mb-1 block">Speed</label>
                    <input
                      type="number"
                      min="1"
                      max="255"
                      value={lbSpeed}
                      onChange={(e) => setLbSpeed(parseInt(e.target.value, 10))}
                      className="w-full bg-slate-900 border border-slate-700 rounded-lg px-2.5 py-2 text-white"
                    />
                  </div>
                </div>

                <div className="flex flex-wrap items-center justify-between gap-3 pt-2 border-t border-slate-900">
                  <div className="flex items-center gap-4 text-xs">
                    <label className="flex items-center gap-1.5 cursor-pointer">
                      <input
                        type="checkbox"
                        checked={lbRainbow}
                        onChange={(e) => setLbRainbow(e.target.checked)}
                        className="accent-cyan-400 rounded"
                      />
                      <span>Rainbow Mode</span>
                    </label>

                    <label className="flex items-center gap-1.5">
                      <span>Direction:</span>
                      <select
                        value={lbDirection}
                        onChange={(e) => setLbDirection(e.target.value as any)}
                        className="bg-slate-900 border border-slate-700 rounded px-2 py-1 text-white"
                      >
                        <option value="right">Right →</option>
                        <option value="left">← Left</option>
                        <option value="bounce">⇄ Bounce</option>
                      </select>
                    </label>
                  </div>

                  <button
                    onClick={() => {
                      const rgb = hexToRgb(lbColor);
                      callService(`activate_light_bar_${lbMode}`, {
                        color: rgb,
                        rainbow: lbRainbow,
                        duration: lbDuration,
                        speed: lbSpeed,
                        direction: lbDirection
                      });
                    }}
                    className="px-4 py-2 bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-slate-950 font-bold rounded-xl text-xs flex items-center gap-1.5 shadow-lg shadow-cyan-500/20"
                  >
                    <Play className="w-3.5 h-3.5 fill-current" />
                    Trigger Effect
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* TAB 3: ALARMS */}
          {activeTab === 'alarms' && (
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col gap-6 shadow-xl animate-in fade-in">
              <div className="flex justify-between items-center">
                <h2 className="text-base font-bold text-white flex items-center gap-2">
                  <Bell className="w-4 h-4 text-cyan-400" />
                  Alarms &amp; Scheduled Wake-ups
                </h2>
              </div>

              {/* Alarm List */}
              <div className="flex flex-col gap-3">
                {activeDevice && Object.values(activeDevice.alarms).map((alarm) => (
                  <div
                    key={alarm.id}
                    className={`p-4 rounded-xl border flex flex-wrap items-center justify-between gap-4 transition-all ${
                      alarm.status === 'active'
                        ? 'bg-rose-950/60 border-rose-600 shadow-lg shadow-rose-900/30'
                        : alarm.status === 'set'
                        ? 'bg-slate-950/70 border-slate-800'
                        : 'bg-slate-950/30 border-slate-900 opacity-60'
                    }`}
                  >
                    <div className="flex items-center gap-3">
                      <div
                        className="w-10 h-10 rounded-xl flex items-center justify-center font-bold text-xs"
                        style={{
                          backgroundColor: rgbToCss(alarm.color, 0.2),
                          color: rgbToCss(alarm.color),
                          border: `1px solid ${rgbToCss(alarm.color, 0.4)}`
                        }}
                      >
                        {alarm.id === 0 ? 'SYS' : `#${alarm.id}`}
                      </div>
                      <div>
                        <div className="flex items-center gap-2">
                          <span className="font-bold text-white text-lg font-clock tracking-wider">{alarm.time}</span>
                          <span className="text-xs text-slate-300 font-semibold">{alarm.name}</span>
                        </div>
                        <div className="text-xs text-slate-400 flex items-center gap-2 mt-0.5">
                          <span>{alarm.repeat.length > 0 ? alarm.repeat.join(', ') : 'Once'}</span>
                          <span>•</span>
                          <span>{alarm.sound}</span>
                          <span>•</span>
                          <span>Vol: {alarm.volume}%</span>
                        </div>
                      </div>
                    </div>

                    <div className="flex items-center gap-2">
                      {/* Status toggle button */}
                      <button
                        onClick={() => {
                          const newStatus = alarm.status === 'set' ? 'Disabled' : 'Enabled';
                          callService('update_alarm', { id: alarm.id, status: newStatus });
                        }}
                        className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${
                          alarm.status === 'set'
                            ? 'bg-emerald-950 border border-emerald-700 text-emerald-300'
                            : 'bg-slate-800 border border-slate-700 text-slate-400'
                        }`}
                      >
                        {alarm.status === 'set' ? 'Armed' : 'Disarmed'}
                      </button>

                      {/* Test trigger button */}
                      <button
                        onClick={async () => {
                          await fetch(`/api/devices/${activeDevice.id}/alarms/${alarm.id}/trigger`, { method: 'POST' });
                          fetchDevices();
                          fetchEvents();
                        }}
                        className="px-2.5 py-1.5 rounded-lg bg-amber-950 border border-amber-800 text-amber-300 text-xs font-semibold hover:bg-amber-900 transition-colors"
                        title="Simulate alarm trigger"
                      >
                        Test Ring
                      </button>

                      {/* Delete button (except sys alarm 0) */}
                      {alarm.id !== 0 && (
                        <button
                          onClick={() => callService('delete_alarm', { id: alarm.id })}
                          className="p-1.5 text-slate-400 hover:text-rose-400 rounded-lg transition-colors"
                          title="Delete alarm"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      )}
                    </div>
                  </div>
                ))}
              </div>

              {/* Add New Alarm Form */}
              <div className="p-4 rounded-xl bg-slate-950/80 border border-slate-800 flex flex-col gap-3">
                <div className="text-sm font-semibold text-white flex items-center gap-1.5">
                  <Plus className="w-4 h-4 text-cyan-400" />
                  Add New Alarm
                </div>

                <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs">
                  <div>
                    <label className="text-slate-400 mb-1 block">Time</label>
                    <input
                      type="time"
                      value={newAlarmTime}
                      onChange={(e) => setNewAlarmTime(e.target.value)}
                      className="w-full bg-slate-900 border border-slate-700 rounded-lg px-2.5 py-1.5 text-white"
                    />
                  </div>

                  <div>
                    <label className="text-slate-400 mb-1 block">Alarm Name</label>
                    <input
                      type="text"
                      value={newAlarmName}
                      onChange={(e) => setNewAlarmName(e.target.value)}
                      className="w-full bg-slate-900 border border-slate-700 rounded-lg px-2.5 py-1.5 text-white"
                    />
                  </div>

                  <div>
                    <label className="text-slate-400 mb-1 block">Sound Track</label>
                    <select
                      value={newAlarmSound}
                      onChange={(e) => setNewAlarmSound(e.target.value)}
                      className="w-full bg-slate-900 border border-slate-700 rounded-lg px-2 py-1.5 text-white"
                    >
                      {ALARM_SOUND_OPTIONS.map(s => (
                        <option key={s} value={s}>{s}</option>
                      ))}
                    </select>
                  </div>

                  <div>
                    <label className="text-slate-400 mb-1 block">Volume ({newAlarmVolume}%)</label>
                    <input
                      type="range"
                      min="10"
                      max="100"
                      value={newAlarmVolume}
                      onChange={(e) => setNewAlarmVolume(parseInt(e.target.value, 10))}
                      className="w-full accent-cyan-400 mt-2"
                    />
                  </div>
                </div>

                <div className="flex justify-between items-center pt-2">
                  <div className="flex items-center gap-1 text-xs">
                    {['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'].map(day => (
                      <button
                        key={day}
                        onClick={() => {
                          setNewAlarmDays(prev =>
                            prev.includes(day) ? prev.filter(d => d !== day) : [...prev, day]
                          );
                        }}
                        className={`w-7 h-7 rounded font-semibold text-[10px] transition-colors ${
                          newAlarmDays.includes(day)
                            ? 'bg-cyan-500 text-slate-950'
                            : 'bg-slate-800 text-slate-400'
                        }`}
                      >
                        {day}
                      </button>
                    ))}
                  </div>

                  <button
                    onClick={() => {
                      callService('add_alarm', {
                        name: newAlarmName,
                        time: newAlarmTime,
                        repeat: newAlarmDays,
                        sound: newAlarmSound,
                        volume: newAlarmVolume,
                        status: 'Enabled',
                        color: hexToRgb(newAlarmColor)
                      });
                    }}
                    className="px-4 py-2 bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-bold rounded-xl text-xs flex items-center gap-1.5 shadow"
                  >
                    <Plus className="w-3.5 h-3.5" />
                    Save Alarm
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* TAB 4: AUDIO & EQ */}
          {activeTab === 'audio' && (
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col gap-6 shadow-xl animate-in fade-in">
              <h2 className="text-base font-bold text-white flex items-center gap-2">
                <Volume2 className="w-4 h-4 text-cyan-400" />
                Acoustics &amp; Sound Profiles
              </h2>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div className="p-4 rounded-xl bg-slate-950/70 border border-slate-800 flex flex-col gap-3">
                  <label className="text-sm font-semibold text-slate-200">Sound Preset EQ</label>
                  <div className="grid grid-cols-3 gap-2">
                    {['Flat', 'Rock', 'Pop', 'Jazz', 'Talk', 'Classical'].map((preset) => (
                      <button
                        key={preset}
                        onClick={() => updateDevice({ soundPreset: preset as any })}
                        className={`py-2 px-3 rounded-lg text-xs font-semibold transition-all ${
                          activeDevice?.soundPreset === preset
                            ? 'bg-purple-600 text-white shadow-md shadow-purple-600/30'
                            : 'bg-slate-800 text-slate-300 hover:bg-slate-700'
                        }`}
                      >
                        {preset}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="p-4 rounded-xl bg-slate-950/70 border border-slate-800 flex flex-col justify-between gap-3">
                  <div>
                    <div className="text-sm font-semibold text-slate-200">Volume Dependent EQ</div>
                    <div className="text-xs text-slate-400">Dynamically adjust bass and treble curve as volume changes</div>
                  </div>
                  <input
                    type="checkbox"
                    checked={activeDevice?.volumeDependentEq ?? true}
                    onChange={(e) => updateDevice({ volumeDependentEq: e.target.checked })}
                    className="w-5 h-5 accent-purple-400 rounded cursor-pointer"
                  />
                </div>

                <div className="p-4 rounded-xl bg-slate-950/70 border border-slate-800 flex flex-col justify-between gap-3">
                  <div>
                    <div className="text-sm font-semibold text-slate-200">Ascending Alarms</div>
                    <div className="text-xs text-slate-400">Gradually increase alarm volume over 60 seconds</div>
                  </div>
                  <input
                    type="checkbox"
                    checked={activeDevice?.ascendingAlarms ?? true}
                    onChange={(e) => updateDevice({ ascendingAlarms: e.target.checked })}
                    className="w-5 h-5 accent-purple-400 rounded cursor-pointer"
                  />
                </div>

                <div className="p-4 rounded-xl bg-slate-950/70 border border-slate-800 flex flex-col justify-between gap-3">
                  <div>
                    <div className="text-sm font-semibold text-slate-200">Alexa Feedback Tones</div>
                    <div className="text-xs text-slate-400">Chime on wake-word detection and tap-to-talk</div>
                  </div>
                  <div className="flex gap-4 text-xs">
                    <label className="flex items-center gap-1.5 cursor-pointer">
                      <input
                        type="checkbox"
                        checked={activeDevice?.alexaWakeWordTone ?? true}
                        onChange={(e) => updateDevice({ alexaWakeWordTone: e.target.checked })}
                        className="accent-purple-400 rounded"
                      />
                      <span>Wake Chime</span>
                    </label>
                    <label className="flex items-center gap-1.5 cursor-pointer">
                      <input
                        type="checkbox"
                        checked={activeDevice?.alexaTapToTalkTone ?? true}
                        onChange={(e) => updateDevice({ alexaTapToTalkTone: e.target.checked })}
                        className="accent-purple-400 rounded"
                      />
                      <span>Tap Chime</span>
                    </label>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* TAB 5: SERVICES API & OVERRIDES */}
          {activeTab === 'services' && (
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col gap-6 shadow-xl animate-in fade-in">
              <h2 className="text-base font-bold text-white flex items-center gap-2">
                <Sliders className="w-4 h-4 text-cyan-400" />
                Live Home Assistant Services Execution
              </h2>

              {/* Service 1: Set Main Display Text */}
              <div className="p-4 rounded-xl bg-slate-950/80 border border-slate-800 flex flex-col gap-3">
                <div className="flex justify-between items-center">
                  <div>
                    <div className="text-sm font-bold text-cyan-300 font-mono">sandman_doppler.set_main_display_text</div>
                    <div className="text-xs text-slate-400">Scroll custom text alert on Doppler's front digits (e.g. "GARBAGE DAY")</div>
                  </div>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                  <input
                    type="text"
                    value={scrollingText}
                    onChange={(e) => setScrollingText(e.target.value.toUpperCase())}
                    placeholder="TEXT (A-Z, 0-9)"
                    className="bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 text-sm font-mono text-white"
                  />
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-slate-400">Duration:</span>
                    <input
                      type="number"
                      min="1"
                      max="60"
                      value={scrollDuration}
                      onChange={(e) => setScrollDuration(parseInt(e.target.value, 10))}
                      className="w-16 bg-slate-900 border border-slate-700 rounded-lg px-2 py-1.5 text-xs text-white"
                    />
                    <span className="text-xs text-slate-500">sec</span>
                  </div>
                  <button
                    onClick={() => {
                      callService('set_main_display_text', {
                        text: scrollingText,
                        duration: scrollDuration,
                        speed: scrollSpeed,
                        color: activeDisplayColor
                      });
                    }}
                    className="px-4 py-1.5 bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-bold rounded-lg text-xs flex items-center justify-center gap-1.5"
                  >
                    <Send className="w-3.5 h-3.5" />
                    Send Text Message
                  </button>
                </div>
              </div>

              {/* Service 2: Set Mini Display Number */}
              <div className="p-4 rounded-xl bg-slate-950/80 border border-slate-800 flex flex-col gap-3">
                <div>
                  <div className="text-sm font-bold text-cyan-300 font-mono">sandman_doppler.set_mini_display_number</div>
                  <div className="text-xs text-slate-400">Display custom number (-199 to 199, e.g. blood sugar CGM or car battery %)</div>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                  <input
                    type="number"
                    min="-199"
                    max="199"
                    value={miniNumber}
                    onChange={(e) => setMiniNumber(parseInt(e.target.value, 10))}
                    className="bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 text-sm font-mono text-white"
                  />
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-slate-400">Duration:</span>
                    <input
                      type="number"
                      min="1"
                      max="120"
                      value={miniDuration}
                      onChange={(e) => setMiniDuration(parseInt(e.target.value, 10))}
                      className="w-16 bg-slate-900 border border-slate-700 rounded-lg px-2 py-1.5 text-xs text-white"
                    />
                    <span className="text-xs text-slate-500">sec</span>
                  </div>
                  <button
                    onClick={() => {
                      callService('set_mini_display_number', {
                        number: miniNumber,
                        duration: miniDuration,
                        color: { r: 255, g: 150, b: 0 }
                      });
                    }}
                    className="px-4 py-1.5 bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-bold rounded-lg text-xs flex items-center justify-center gap-1.5"
                  >
                    <Send className="w-3.5 h-3.5" />
                    Display Number
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* TAB 6: NATIVE ANDROID ARCHITECTURE */}
          {activeTab === 'android' && (
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col gap-6 shadow-xl animate-in fade-in">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <div className="w-8 h-8 rounded-lg bg-emerald-500/20 text-emerald-400 flex items-center justify-center">
                    <Smartphone className="w-5 h-5" />
                  </div>
                  <div>
                    <h2 className="text-base font-bold text-white">Standalone Native Android Architecture</h2>
                    <p className="text-xs text-slate-400">Kotlin, Jetpack Compose, Material 3, OkHttp &amp; Local Wi-Fi Client</p>
                  </div>
                </div>
                <span className="px-2.5 py-1 rounded-full text-xs font-semibold bg-emerald-950 text-emerald-300 border border-emerald-800">
                  Ready in /android
                </span>
              </div>

              {/* Architecture Highlights */}
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-xs">
                <div className="p-3.5 rounded-xl bg-slate-950 border border-slate-800">
                  <div className="font-semibold text-emerald-400 mb-1">Direct Local Wi-Fi</div>
                  <div className="text-slate-400">Connects directly over HTTP to Doppler LAN IP. Zero Home Assistant or cloud bridge required.</div>
                </div>
                <div className="p-3.5 rounded-xl bg-slate-950 border border-slate-800">
                  <div className="font-semibold text-cyan-400 mb-1">Encrypted Keystore</div>
                  <div className="text-slate-400">Android Keystore for tokens &amp; credentials. No plain-text storage or secret logging.</div>
                </div>
                <div className="p-3.5 rounded-xl bg-slate-950 border border-slate-800">
                  <div className="font-semibold text-purple-400 mb-1">Kotlin Coroutines &amp; Flow</div>
                  <div className="text-slate-400">Reactive MVI/MVVM ViewModel state, optimistic updates, and background safety.</div>
                </div>
              </div>

              {/* File Tree Inspector */}
              <div className="p-4 rounded-xl bg-slate-950 border border-slate-800 font-mono text-xs text-slate-300">
                <div className="text-slate-400 font-bold mb-2 flex items-center gap-2">
                  <FileCode className="w-4 h-4 text-emerald-400" />
                  Android Project Structure (`/android/`)
                </div>
                <pre className="text-[11px] leading-relaxed text-slate-400 overflow-x-auto">
{`android/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   └── java/com/paloalto/sandmandoppler/
        │       ├── MainActivity.kt
        │       ├── data/
        │       │   ├── api/DopplerLocalApi.kt
        │       │   ├── model/DopplerModels.kt
        │       │   └── repository/DopplerRepository.kt
        │       ├── ui/
        │       │   ├── theme/Theme.kt
        │       │   ├── screens/DashboardScreen.kt
        │       │   ├── screens/LightingScreen.kt
        │       │   ├── screens/AlarmScreen.kt
        │       │   └── viewmodel/DopplerViewModel.kt
        │       └── security/TokenStore.kt
        └── test/
            └── java/com/paloalto/sandmandoppler/DopplerClientTest.kt`}
                </pre>
              </div>
            </div>
          )}

          {/* TAB 7: EVENT LOG & WEBHOOKS */}
          {activeTab === 'events' && (
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col gap-4 shadow-xl animate-in fade-in">
              <div className="flex justify-between items-center">
                <h2 className="text-base font-bold text-white flex items-center gap-2">
                  <Activity className="w-4 h-4 text-cyan-400" />
                  Live Event Log &amp; Webhooks
                </h2>
                <button
                  onClick={async () => {
                    await fetch('/api/events/clear', { method: 'POST' });
                    setEvents([]);
                  }}
                  className="text-xs text-slate-400 hover:text-slate-200 transition-colors"
                >
                  Clear Logs
                </button>
              </div>

              <div className="flex flex-col gap-2 max-h-[420px] overflow-y-auto pr-1">
                {events.length === 0 ? (
                  <div className="text-center py-10 text-xs text-slate-500">No events logged yet. Press a button or trigger a service!</div>
                ) : (
                  events.map(evt => (
                    <div
                      key={evt.id}
                      className="p-3 rounded-xl bg-slate-950 border border-slate-800 text-xs font-mono flex flex-col gap-1"
                    >
                      <div className="flex items-center justify-between">
                        <span className="font-bold text-cyan-400">{evt.title}</span>
                        <span className="text-[10px] text-slate-500">{new Date(evt.timestamp).toLocaleTimeString()}</span>
                      </div>
                      <div className="text-[11px] text-slate-400">
                        Device: {evt.deviceName} ({evt.deviceId})
                      </div>
                      <pre className="text-[10px] text-slate-400 bg-slate-900/60 p-2 rounded overflow-x-auto mt-1">
                        {JSON.stringify(evt.data, null, 2)}
                      </pre>
                    </div>
                  ))
                )}
              </div>
            </div>
          )}

        </div>
      </div>
    </div>
  );
}
