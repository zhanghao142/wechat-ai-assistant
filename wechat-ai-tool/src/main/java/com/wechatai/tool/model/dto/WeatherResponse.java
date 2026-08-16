package com.wechatai.tool.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeatherResponse {

    private String province;
    private String city;
    private String adcode;
    private String weather;
    private Integer temperature;

    @JsonProperty("wind_direction")
    private String windDirection;

    @JsonProperty("wind_power")
    private String windPower;

    private Integer humidity;

    @JsonProperty("report_time")
    private String reportTime;

    @JsonProperty("temp_max")
    private Integer tempMax;

    @JsonProperty("temp_min")
    private Integer tempMin;

    @JsonProperty("feels_like")
    private String feelsLike;

    private String visibility;

    private String pressure;

    private String uv;

    private Integer aqi;

    @JsonProperty("aqi_level")
    private Integer aqiLevel;

    @JsonProperty("aqi_category")
    private String aqiCategory;

    @JsonProperty("aqi_primary")
    private String aqiPrimary;

    @JsonProperty("air_pollutants")
    private AirPollutants airPollutants;

    private List<ForecastDay> forecast;

    @JsonProperty("life_indices")
    private LifeIndices lifeIndices;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AirPollutants {
        private Double pm25;
        private Double pm10;
        private Double o3;
        private Double no2;
        private Double so2;
        private Double co;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ForecastDay {
        private String date;
        private String week;

        @JsonProperty("temp_max")
        private Integer tempMax;

        @JsonProperty("temp_min")
        private Integer tempMin;

        @JsonProperty("weather_day")
        private String dayWeather;

        @JsonProperty("weather_night")
        private String nightWeather;

        @JsonProperty("wind_dir_day")
        private String dayWindDirection;

        @JsonProperty("wind_dir_night")
        private String nightWindDirection;

        @JsonProperty("wind_scale_day")
        private String dayWindPower;

        @JsonProperty("wind_scale_night")
        private String nightWindPower;

        private String sunrise;
        private String sunset;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LifeIndices {
        private IndexItem clothing;
        private IndexItem uv;

        @JsonProperty("car_wash")
        private IndexItem carWash;

        private IndexItem umbrella;
        private IndexItem exercise;
        private IndexItem drying;

        @JsonProperty("air_conditioner")
        private IndexItem airConditioner;

        @JsonProperty("cold_risk")
        private IndexItem coldRisk;

        private IndexItem comfort;
        private IndexItem travel;
        private IndexItem fishing;
        private IndexItem allergy;
        private IndexItem sunscreen;
        private IndexItem mood;
        private IndexItem beer;
        private IndexItem traffic;

        @JsonProperty("air_purifier")
        private IndexItem airPurifier;

        private IndexItem pollen;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IndexItem {
        private String level;
        private String brief;
        private String advice;
    }
}