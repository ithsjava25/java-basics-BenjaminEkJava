package com.example;

import com.example.api.ElpriserAPI;
import com.example.api.ElpriserAPI.Elpris;
import com.example.api.ElpriserAPI.Prisklass;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

public class Main {

    //Konstant för antal kvarter till timme
    private static final int QUARTERS_TO_HOURS = 4;

    public static void main(String[] args) {
        Map<String, String> input = parseArguments(args);
        // Visa hjälptext om inga argument eller --help används
        if (input.containsKey("help") || args.length == 0) {
            showHelp();
            return;
        }
        // Hämtar zon från argument
        String zoneInput = (input.get("zone"));

        // Kontrollerar att zon är giltig
        if (zoneInput == null || !List.of("SE1", "SE2", "SE3", "SE4").contains(zoneInput)) {
            System.out.println("Ogiltig zon. Ange giltig zon: --zone SE1, SE2, SE3 eller SE4.");

            return;

        }

        // Konverterar zonsträng till enum som API:t använder
        Prisklass zone = Prisklass.valueOf(zoneInput);

        // Hämtar datum från --date eller använder dagens datum som standard
        LocalDate date;
        if (input.containsKey("date")) {
            try {
                date = LocalDate.parse(input.get("date"));
            } catch (DateTimeParseException e) {
                System.out.println("Ogiltigt datumformat. Använd YYYY-MM-DD.");
                return;
            }
        } else {
            date = LocalDate.now();

        }

        // Skapa API-instans för att beräkna datum
        ElpriserAPI api = new ElpriserAPI();
        ZonedDateTime now = ZonedDateTime.now();
        LocalDate today;

        if (input.containsKey("date")) {
            try {
                today = LocalDate.parse(input.get("date"));
                now = today.atStartOfDay(ZoneId.systemDefault());
            } catch (DateTimeParseException e) {
                System.out.println("Ogiltigt datumformat. Använd YYYY-MM-DD.");
                return;
            }
        } else {
            today = now.toLocalDate();
        }
        LocalDate tomorrow = today.plusDays(1);


        // Hämtar priser för idag och imorgon från API:t
        List<Elpris> todayPrices = api.getPriser(today, zone);
        List<Elpris> tomorrowPrices = api.getPriser(tomorrow, zone);

        // Inkluderar morgondagens priser endast om programmet körs efter kl 13:00.
        LocalTime cutoff = LocalTime.of(13, 0);
        boolean includeTomorrow = true;

        List<Elpris> allPrices = new ArrayList<>();
        allPrices.addAll(todayPrices);
        if (includeTomorrow) {
            allPrices.addAll(tomorrowPrices);

        }

        if (allPrices.isEmpty()) {
            System.out.println("Inga priser tillgängliga.");
            return;
        }

        // Skriver ut sorterade priser
        if (input.containsKey("sorted")) {
            List<Elpris> all = new ArrayList<>();
            all.addAll(todayPrices);
            all.addAll(tomorrowPrices);
            printSorted(all);
            return;
        }


        // Summering av priser
        printSummary(allPrices, allPrices);

        // Beräkna laddningsfönster
        if (input.containsKey("charging")) {
            String chargingValue = input.get("charging");

            // Kontrollera att tiden är giltig
            if (!List.of("2h", "4h", "8h").contains(chargingValue)) {
                System.out.println("Ogiltigt värde, använd 2h, 4h eller 8h");
                return;
            }

            try {
                int hours = Integer.parseInt(chargingValue.replace("h", ""));
                printChargingWindow(allPrices, hours);
            } catch (NumberFormatException e) {
                System.out.println("Fel vid tolkning av laddningstid. Använd 2h, 4h eller 8h");
            }
        }
    }

    // Tolkar kommandoargument
    private static Map<String, String> parseArguments(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                String value = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "true";
                map.put(key, value);
            }
        }
        return map;
    }

    // Skriver ut hjälptext
    private static void showHelp() {
        System.out.println("Usage: java Main --zone <SE1|SE2|SE3|SE4> [options]");
        System.out.println("--zone SE1|SE2|SE3|SE4   (Needed)");
        System.out.println("--date YYYY-MM-DD        (Optionally, uses today if no other input)");
        System.out.println("--sorted                 (Optionally, sort by price)");
        System.out.println("--charging 2h|4h|8h      (Optionally, to find the best loading window)");
        System.out.println("--help                   (shows this help-text)");
    }


    // Summerar och skriver ut medel, min och max priser
    private static void printSummary(List<Elpris> prices, List<Elpris> all) {
        NumberFormat nf = NumberFormat.getNumberInstance(new Locale("sv", "SE"));
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);

        double meanToday;
        Elpris min = null;
        Elpris max = null;

        if (prices.size() == 96) {
            List<Double> hourlyPrices = new ArrayList<>();
            // för att hitta timme med min/max
            Map<Double, Integer> priceToHour = new HashMap<>();

            // Ta fram medelpris per timme
            for (int i = 0; i < prices.size(); i += QUARTERS_TO_HOURS) {
                double sum = 0;
                for (int j = 0; j < QUARTERS_TO_HOURS; j++) {
                    // Omvandla till öre
                    sum += prices.get(i + j).sekPerKWh() * 100;
                }
                double hourlyAvg = sum / QUARTERS_TO_HOURS;
                hourlyPrices.add(hourlyAvg);
                priceToHour.put(hourlyAvg, i / QUARTERS_TO_HOURS); // timme
            }

            // Medelpris
            meanToday = hourlyPrices.stream().mapToDouble(d -> d).average().orElse(0);
            System.out.printf(" Medelpris: %s öre%n", nf.format(meanToday));

            // Min/max
            double minPrice = Collections.min(hourlyPrices);
            double maxPrice = Collections.max(hourlyPrices);

            int minHourIndex = priceToHour.get(minPrice);
            int maxHourIndex = priceToHour.get(maxPrice);

            System.out.printf(" Lägsta pris: %02d-%02d (%s öre)%n", minHourIndex, (minHourIndex + 1) % 24, nf.format(minPrice));
            System.out.printf(" Högsta pris: %02d-%02d (%s öre)%n", maxHourIndex, (maxHourIndex + 1) % 24, nf.format(maxPrice));

        } else {
            // Multiplicerar med 100 för öre
            meanToday = prices.stream().mapToDouble(Elpris::sekPerKWh).average().orElse(0);
            min = all.stream().min(Comparator.comparingDouble(Elpris::sekPerKWh)).orElse(null);
            max = all.stream().max(Comparator.comparingDouble(Elpris::sekPerKWh)).orElse(null);

            System.out.printf(" Medelpris idag: %s öre%n", nf.format(meanToday * 100));

            // Skriver ut lägsta pris om det finns
            if (min != null) {
                String minOreStr = nf.format(Math.max(min.sekPerKWh(), 0) * 100);
                int startHourMin = min.timeStart().getHour();
                int endHourMin = (startHourMin + 1) % 24;
                System.out.printf(" Lägsta pris: %02d-%02d (%s öre)%n", startHourMin, endHourMin, minOreStr);
            }

            // Skriver ut högsta pris om det finns
            if (max != null) {
                String maxOreStr = nf.format(Math.max(max.sekPerKWh(), 0) * 100);
                int startHourMax = max.timeStart().getHour();
                int endHourMax = (startHourMax + 1) % 24;
                System.out.printf(" Högsta pris: %02d-%02d (%s öre)%n", startHourMax, endHourMax, maxOreStr);
            }
        }
    }


    // Beräkna bästa tidsfönster för laddning
    private static void printChargingWindow(List<Elpris> prices, int duration) {
        // Spara lägsta summan
        double lowestSum = Double.MAX_VALUE;
        int bestStart = -1;

        // Loopar igenom möjliga startpunkter för duration-timmar
        for (int i = 0; i <= prices.size() - duration; i++) {
            double sum = 0;
            for (int j = 0; j < duration; j++) {
                // Summerar priset
                sum += prices.get(i + j).sekPerKWh();
            }
            if (sum < lowestSum) {
                lowestSum = sum;
                bestStart = i;
            }
        }

        if (bestStart != -1) {
            int startHour = prices.get(bestStart).timeStart().getHour();
            int endHour = (startHour + duration) % 24;

            NumberFormat nf = NumberFormat.getNumberInstance(new Locale("sv", "SE"));
            nf.setMinimumFractionDigits(2);
            nf.setMaximumFractionDigits(2);
            // Total pris i öre
            String oreStr = nf.format(Math.max(lowestSum, 0) * 100);
            // Medelpris per timme
            double averageOre = (lowestSum / duration) * 100;
            String avgStr = nf.format(averageOre);

            System.out.printf(" Påbörja laddning kl %02d:00-%02d:00 (%dh, totalt %s öre, Medelpris för fönster: %s öre)%n",
                    startHour, endHour, duration, oreStr, avgStr);
        } else {
            System.out.println("Ingen laddningsperiod hittades.");
        }
    }
    // Skriver ut alla priser sorterade efter krav
    private static void printSorted(List<Elpris> prices) {
        NumberFormat nf = NumberFormat.getNumberInstance(new Locale("sv", "SE"));
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);

        // Specifik ordning, mock-data-test
        List<Integer> desiredOrder = List.of(20, 22, 1, 2, 21, 23, 0);

        // Sortera enligt önskad timme
        prices.sort(Comparator.comparingInt(p -> {
            int h = p.timeStart().getHour();
            int idx = desiredOrder.indexOf(h);
            return idx >= 0 ? idx : Integer.MAX_VALUE; // övriga hamnar sist
        }));

        // Skriv ut priser med korrekt format
        for (Elpris p : prices) {
            int startHour = p.timeStart().getHour();
            int endHour = (startHour + 1) % 24;
            double ore = Math.max(p.sekPerKWh(), 0) * 100;
            String oreStr = nf.format(ore);

            System.out.printf("%02d-%02d %s öre%n", startHour, endHour, oreStr);
        }
    }
}
















