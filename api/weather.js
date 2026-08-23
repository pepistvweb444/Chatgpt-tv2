export default async function handler(req, res) {
  try {
    const forwarded = String(req.headers['x-forwarded-for'] || '').split(',')[0].trim();
    const clientIp = forwarded && !forwarded.startsWith('10.') && forwarded !== '127.0.0.1' ? forwarded : '';

    let lat = Number(req.query?.lat || 0);
    let lon = Number(req.query?.lon || 0);
    let place = String(req.query?.place || '').trim();

    // If the app asks for a city/place explicitly, geocode that place first.
    if ((!lat || !lon) && place) {
      const geoUrl = new URL('https://geocoding-api.open-meteo.com/v1/search');
      geoUrl.searchParams.set('name', place);
      geoUrl.searchParams.set('count', '1');
      geoUrl.searchParams.set('language', 'es');
      geoUrl.searchParams.set('format', 'json');
      const geo = await fetch(geoUrl);
      if (geo.ok) {
        const g = await geo.json();
        const first = Array.isArray(g?.results) ? g.results[0] : null;
        if (first) {
          lat = Number(first.latitude || 0);
          lon = Number(first.longitude || 0);
          place = [first.name, first.admin1, first.country].filter(Boolean).join(', ');
        }
      }
    }

    if ((!lat || !lon) && clientIp) {
      const geo = await fetch(`https://ipwho.is/${encodeURIComponent(clientIp)}`);
      if (geo.ok) {
        const g = await geo.json();
        if (g?.success !== false) {
          lat = Number(g.latitude || 0);
          lon = Number(g.longitude || 0);
          place = place || [g.city, g.region].filter(Boolean).join(', ');
        }
      }
    }

    if (!lat || !lon) return res.status(400).json({ error: 'location_unavailable' });

    const url = new URL('https://api.open-meteo.com/v1/forecast');
    url.searchParams.set('latitude', String(lat));
    url.searchParams.set('longitude', String(lon));
    url.searchParams.set('current', 'temperature_2m,apparent_temperature,weather_code,wind_speed_10m');
    url.searchParams.set('daily', 'weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max');
    url.searchParams.set('timezone', 'auto');
    url.searchParams.set('forecast_days', '4');

    const r = await fetch(url);
    if (!r.ok) return res.status(r.status).json({ error: 'weather_provider_error' });
    const w = await r.json();
    const current = w.current || {};
    const daily = w.daily || {};
    const days = (daily.time || []).map((date, i) => ({
      date,
      code: daily.weather_code?.[i],
      max: daily.temperature_2m_max?.[i],
      min: daily.temperature_2m_min?.[i],
      rain: daily.precipitation_probability_max?.[i]
    }));

    res.setHeader('Cache-Control', 'public, max-age=300, s-maxage=300');
    return res.status(200).json({
      place: place || 'Ubicación actual',
      temperature: current.temperature_2m,
      feelsLike: current.apparent_temperature,
      code: current.weather_code,
      wind: current.wind_speed_10m,
      days
    });
  } catch (e) {
    return res.status(500).json({ error: e?.message || 'weather_error' });
  }
}
