/*
 * Copyright (C) 2013 Marten Gajda <marten@dmfs.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package org.dmfs.rfc5545.recur;

import java.util.List;
import java.util.TreeSet;

import org.dmfs.rfc5545.recur.RecurrenceRule.Freq;
import org.dmfs.rfc5545.recur.RecurrenceRule.Part;


/**
 * A filter that limits or expands recurrence rules by week of year. In <a href="http://tools.ietf.org/html/rfc5545#section-3.3.10">RFC 5545</a> this is allowed
 * for YEARLY rules only. However since RFC 2445 allows this part with every frequency we handle all combinations.
 * <p>
 * In particular that means YEARLY and MONTHLY rules are expanded and all other frequencies are filtered.
 * </p>
 * <p>
 * In case of MONTHLY rules or YEARLY rules with BYMONTH part this filter expands also weeks overlap the expanded month if any BY*DAY rules follows. That means
 * two subsequent interval sets can include the same week. The BY*DAY filters will take care of filtering those.
 * </p>
 * 
 * @author Marten Gajda <marten@dmfs.org>
 */
final class ByWeekNoFilter extends ByFilter
{
	/**
	 * The week number to let pass or the expand.
	 */
	private final List<Integer> mByWeekNo;

	/**
	 * The scope of this part.
	 */
	private final Scope mScope;

	/**
	 * A helper for calendar calculations.
	 */
	private final Calendar mHelper = new Calendar(Calendar.UTC, 2000, 0, 1, 0, 0, 0);

	/**
	 * A flag that indicates that we have to expand weeks that overlap a month.
	 */
	private final boolean mAllowOverlappingWeeks;


	public ByWeekNoFilter(RecurrenceRule rule, RuleIterator previous, Calendar start)
	{
		super(previous, start, rule.getFreq() == Freq.YEARLY || rule.getFreq() == Freq.MONTHLY);

		mByWeekNo = rule.getByPart(Part.BYWEEKNO);

		mScope = rule.getFreq() == Freq.MONTHLY || rule.getFreq() == Freq.YEARLY && rule.hasPart(Part.BYMONTH) ? Scope.MONTHLY : Scope.YEARLY;

		// allow overlapping weeks in MONTHLY scope and if any BY*DAY rule is present
		mAllowOverlappingWeeks = mScope == Scope.MONTHLY && (rule.hasPart(Part.BYDAY) || rule.hasPart(Part.BYMONTHDAY) || rule.hasPart(Part.BYYEARDAY));

		// initialize helper
		mHelper.setFirstDayOfWeek(rule.getWeekStart().toCalendarDay());
		mHelper.setMinimalDaysInFirstWeek(4);
	}


	@Override
	boolean filter(Instance instance)
	{
		/*
		 * RFC 5545 doesn't specify filtering for BYWEEKNO, it's allowed for expansion of YEARLY rules, only. However RFC 2445 doesn't have any restrictions, so
		 * we should be able to filter BYWEEKNO.
		 */
		mHelper.set(instance.year, instance.month, 1);
		// get the number of weeks in that year
		int yearWeeks = mHelper.getActualMaximum(Calendar.WEEK_OF_YEAR);
		return (!mByWeekNo.contains(instance.weekOfYear) && !mByWeekNo.contains(instance.weekOfYear - 1 - yearWeeks)) || instance.weekOfYear > yearWeeks;
	}


	@Override
	void expand(TreeSet<Instance> set, Instance instance, Instance notBefore)
	{
		mHelper.set(instance.year, instance.month, 1);
		// get the number of weeks in that year
		int yearWeeks = mHelper.getActualMaximum(Calendar.WEEK_OF_YEAR);

		for (int weekOfYear : mByWeekNo)
		{
			int actualWeek = weekOfYear;
			if (weekOfYear < 0)
			{
				actualWeek = yearWeeks + weekOfYear + 1;
			}

			if (actualWeek <= 0 || actualWeek > yearWeeks)
			{
				continue;
			}

			if (mScope == Scope.MONTHLY && mAllowOverlappingWeeks)
			{
				/*
				 * Expand instances if the week intersects instance.month. The by-day expansion will filter any instances not in that month.
				 */
				mHelper.set(instance.year, instance.month, instance.dayOfMonth, instance.hour, instance.minute, instance.second);
				mHelper.set(Calendar.WEEK_OF_YEAR, actualWeek);
				// maintain original day of week
				mHelper.set(Calendar.DAY_OF_WEEK, instance.dayOfWeek);
				if (mHelper.get(Calendar.MONTH) == instance.month)
				{
					set.add(new Instance(mHelper));
				}
				else
				{
					int firstDayOfWeek = mHelper.getFirstDayOfWeek();

					// check if the first day of this week is still in this month
					mHelper.set(Calendar.DAY_OF_WEEK, firstDayOfWeek);
					if (mHelper.get(Calendar.MONTH) == instance.month)
					{
						// create a new instance and adjust day values
						Instance newInstance = new Instance(mHelper);
						newInstance.dayOfWeek = instance.dayOfWeek;
						int offset = (instance.dayOfWeek - firstDayOfWeek + 7) % 7;
						newInstance.dayOfMonth += offset;
						newInstance.dayOfYear += offset;
						set.add(newInstance);
					}
					else
					{
						// check if the last day of this week is still in this month
						mHelper.add(Calendar.DAY_OF_YEAR, 6);
						if (mHelper.get(Calendar.MONTH) == instance.month)
						{
							// create a new instance and adjust day values
							Instance newInstance = new Instance(mHelper);
							newInstance.dayOfWeek = instance.dayOfWeek;
							int offset = (instance.dayOfWeek - firstDayOfWeek - 6) % 7;
							newInstance.dayOfMonth += offset;
							newInstance.dayOfYear += offset;
							set.add(newInstance);
						}
					}
				}
			}
			else if (mScope == Scope.MONTHLY)
			{
				/*
				 * Expand instances that are in instance.month.
				 */
				mHelper.set(instance.year, instance.month, instance.dayOfMonth, instance.hour, instance.minute, instance.second);
				mHelper.set(Calendar.WEEK_OF_YEAR, actualWeek);
				mHelper.set(Calendar.DAY_OF_WEEK, instance.dayOfWeek);
				if (mHelper.get(Calendar.MONTH) == instance.month)
				{
					set.add(new Instance(mHelper));
				}
			}
			else
			{
				// mScope == Scope.YEARLY
				mHelper.set(instance.year, instance.month, instance.dayOfMonth, instance.hour, instance.minute, instance.second);
				mHelper.set(Calendar.WEEK_OF_YEAR, actualWeek);
				mHelper.set(Calendar.DAY_OF_WEEK, instance.dayOfWeek);
				set.add(new Instance(mHelper));
			}
		}
	}
}